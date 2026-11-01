package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.*;
import com.ecommerce.project.payload.OrderDTO;
import com.ecommerce.project.payload.OrderItemDTO;
import com.ecommerce.project.payload.OrderResponse;
import com.ecommerce.project.repositories.*;
import com.ecommerce.project.util.AuthUtil;
import com.stripe.exception.StripeException;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    CartRepository cartRepository;

    @Autowired
    AddressRepository addressRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    CartService cartService;

    @Autowired
    ModelMapper modelMapper;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    AuthUtil authUtil;

    @Autowired
    StripeService stripeService;

    // Self-injection so the retry loop calls placeOrderTransactional THROUGH the Spring proxy,
    // giving each attempt a brand-new transaction. @Lazy avoids a circular-dependency failure at startup.
    @Autowired
    @Lazy
    private OrderServiceImpl self;

    // Total attempts (including the first) when a concurrent order changed the product version underneath us.
    // Configurable only so measurements can compare against a no-retry baseline (max-attempts=1).
    @Value("${order.stock.max-attempts:3}")
    private int maxStockAttempts;

    @Override
    public OrderDTO placeOrder(String emailId, Long addressId, String paymentMethod, String pgName, String pgPaymentId, String pgStatus, String pgResponseMessage) {
        int attempt = 0;
        while (true) {
            try {
                // Each call opens a new transaction and re-reads the cart/products with the latest version.
                return self.placeOrderTransactional(emailId, addressId, paymentMethod, pgName, pgPaymentId, pgStatus, pgResponseMessage);
            } catch (OptimisticLockingFailureException ex) {
                attempt++;
                if (attempt >= maxStockAttempts) {
                    // Retries exhausted: the local order rolled back, but the payment was already
                    // captured externally. Compensate by refunding, then fail the request.
                    compensatePayment(pgName, pgPaymentId, "order placement failed after concurrent stock conflicts");
                    throw new APIException("Order could not be placed due to high demand on one of the products. Your payment has been refunded.");
                }
                // Loop again: the next attempt re-fetches fresh stock and version, then re-applies the decrement.
            } catch (RuntimeException ex) {
                // Any other failure (insufficient stock, missing cart/address, unexpected error):
                // the DB transaction already rolled back, so the local state is clean, but the money
                // is still taken at the payment gateway. Refund it as a compensating transaction.
                compensatePayment(pgName, pgPaymentId, ex.getMessage());
                throw ex;
            }
        }
    }

    /**
     * Compensating transaction for the external payment. The order is stored in a local DB
     * transaction, so a failure there is rolled back automatically — but the Stripe charge lives
     * outside that boundary and cannot be rolled back, only refunded. Best-effort: a failed refund
     * must never mask the original error, but it is logged loudly for manual follow-up.
     */
    private void compensatePayment(String pgName, String pgPaymentId, String reason) {
        // Nothing captured externally (e.g. cash on delivery / no gateway id) → nothing to refund.
        if (pgPaymentId == null || pgPaymentId.isBlank() || !"Stripe".equalsIgnoreCase(pgName)) {
            return;
        }
        try {
            stripeService.refundPayment(pgPaymentId);
            log.warn("Refunded payment {} after failed order placement. Reason: {}", pgPaymentId, reason);
        } catch (StripeException e) {
            // Refund itself failed: money is stuck and needs out-of-band reconciliation.
            log.error("CRITICAL: refund failed for payment {} (order not placed, reason: {}). Manual intervention required.",
                    pgPaymentId, reason, e);
        }
    }

    @Transactional
    public OrderDTO placeOrderTransactional(String emailId, Long addressId, String paymentMethod, String pgName, String pgPaymentId, String pgStatus, String pgResponseMessage) {
        Cart cart = cartRepository.findCartByEmail(emailId);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart", "email", emailId);
        }

        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address", "addressId", addressId));

        Order order = new Order();
        order.setEmail(emailId);
        order.setOrderDate(LocalDate.now());
        order.setTotalAmount(cart.getTotalPrice());
        order.setOrderStatus("Accepted");
        order.setAddress(address);

        Payment payment = new Payment(paymentMethod, pgPaymentId, pgStatus, pgResponseMessage, pgName);
        payment.setOrder(order);
        payment = paymentRepository.save(payment);
        order.setPayment(payment);

        Order savedOrder = orderRepository.save(order);

        List<CartItem> cartItems = cart.getCartItems();
        if (cartItems.isEmpty()) {
            throw new APIException("Cart is empty");
        }

        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(cartItem.getProduct());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setDiscount(cartItem.getDiscount());
            orderItem.setOrderedProductPrice(cartItem.getProductPrice());
            orderItem.setOrder(savedOrder);
            orderItems.add(orderItem);
        }

        orderItems = orderItemRepository.saveAll(orderItems);

        cart.getCartItems().forEach(item -> {
            int quantity = item.getQuantity();
            Product product = item.getProduct();

            // Re-validate against the freshly read stock. On a retry this runs against the latest
            // quantity, so we fail cleanly instead of letting stock go negative (oversell).
            if (product.getQuantity() < quantity) {
                throw new APIException("Not enough stock for product '" + product.getProductName()
                        + "'. Available: " + product.getQuantity() + ", requested: " + quantity + ".");
            }

            // Reduce stock quantity. The @Version column makes this save fail with an
            // OptimisticLockingFailureException if another order changed the row concurrently.
            product.setQuantity(product.getQuantity() - quantity);

            // Save product back to the database
            productRepository.save(product);

            // Remove items from cart
            cartService.deleteProductFromCart(cart.getCartId(), item.getProduct().getProductId());
        });

        OrderDTO orderDTO = modelMapper.map(savedOrder, OrderDTO.class);
        orderItems.forEach(item -> orderDTO.getOrderItems().add(modelMapper.map(item, OrderItemDTO.class)));

        orderDTO.setAddressId(addressId);

        return orderDTO;
    }

    @Override
    public OrderResponse getAllOrders(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);
        Page<Order> pageOrders = orderRepository.findAll(pageDetails);
        List<Order> orders = pageOrders.getContent();
        List<OrderDTO> orderDTOs = orders.stream()
                .map(order -> modelMapper.map(order, OrderDTO.class))
                .toList();
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setContent(orderDTOs);
        orderResponse.setPageNumber(pageOrders.getNumber());
        orderResponse.setPageSize(pageOrders.getSize());
        orderResponse.setTotalElements(pageOrders.getTotalElements());
        orderResponse.setTotalPages(pageOrders.getTotalPages());
        orderResponse.setLastPage(pageOrders.isLast());
        return orderResponse;
    }

    @Override
    public OrderDTO updateOrder(Long orderId, String status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order","orderId",orderId));
        order.setOrderStatus(status);
        orderRepository.save(order);
        return modelMapper.map(order, OrderDTO.class);
    }

    @Override
    public OrderResponse getAllSellerOrders(Integer pageNumber, Integer pageSize, String sortBy, String sortOrder) {
        Sort sortByAndOrder = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageDetails = PageRequest.of(pageNumber, pageSize, sortByAndOrder);

        User seller = authUtil.loggedInUser();

        Page<Order> pageOrders = orderRepository.findAll(pageDetails);

        List<Order> sellerOrders = pageOrders.getContent().stream()
                .filter(order -> order.getOrderItems().stream()
                        .anyMatch(orderItem -> {
                            var product = orderItem.getProduct();
                            if (product == null || product.getUser() == null) {
                                return false;
                            }
                            return product.getUser().getUserId().equals(
                                    seller.getUserId());
                        }))
                .toList();

        List<OrderDTO> orderDTOs = sellerOrders.stream()
                .map(order -> modelMapper.map(order, OrderDTO.class))
                .toList();
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setContent(orderDTOs);
        orderResponse.setPageNumber(pageOrders.getNumber());
        orderResponse.setPageSize(pageOrders.getSize());
        orderResponse.setTotalElements(pageOrders.getTotalElements());
        orderResponse.setTotalPages(pageOrders.getTotalPages());
        orderResponse.setLastPage(pageOrders.isLast());
        return orderResponse;
    }


}
