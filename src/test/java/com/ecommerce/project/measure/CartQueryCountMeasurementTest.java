package com.ecommerce.project.measure;

import com.ecommerce.project.model.*;
import com.ecommerce.project.repositories.*;
import com.ecommerce.project.service.CartService;
import com.ecommerce.project.service.ProductService;
import com.ecommerce.project.service.StripeService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Counts the SQL statements issued by cart lookup (CartService.getCart) and product listing
 * for one cart holding ITEMS distinct products. Run once per code version with -Dmeasure.label=<label>;
 * the test and data are identical between runs, only the main code differs.
 * Each call runs in a read-only transaction to mirror open-session-in-view during a web request.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "measure", matches = "true")
class CartQueryCountMeasurementTest extends MeasurementSupport {

    static final int ITEMS = Integer.getInteger("measure.cartItems", 10);
    static final int REPEATS = Integer.getInteger("measure.repeats", 3);
    static final String LABEL = System.getProperty("measure.label", "unlabeled");

    @MockitoBean
    StripeService stripeService;

    @Autowired CartService cartService;
    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired UserRepository userRepository;
    @Autowired CartRepository cartRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void measure() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        String prefix = "nq" + LABEL;
        Long[] ids = tx.execute(status -> {
            User seller = userRepository.save(new User(prefix + "_seller", prefix + "_seller@measure.test", "measure-password"));
            Category category = new Category();
            category.setCategoryName("Measure category " + prefix);
            category = categoryRepository.save(category);

            User buyer = userRepository.save(new User(prefix + "_buyer", prefix + "_buyer@measure.test", "measure-password"));
            Cart cart = new Cart();
            cart.setUser(buyer);
            for (int i = 0; i < ITEMS; i++) {
                Product product = new Product();
                product.setProductName("NPlus " + prefix + " " + i);
                product.setDescription("query count measurement product");
                product.setQuantity(100);
                product.setPrice(100);
                product.setSpecialPrice(100);
                product.setCategory(category);
                product.setUser(seller);
                product = productRepository.save(product);

                CartItem item = new CartItem();
                item.setCart(cart);
                item.setProduct(product);
                item.setQuantity(1);
                item.setProductPrice(100);
                cart.getCartItems().add(item);
            }
            cart.setTotalPrice(100.0 * ITEMS);
            return new Long[]{cartRepository.save(cart).getCartId()};
        });
        String buyerEmail = prefix + "_buyer@measure.test";
        Long cartId = ids[0];

        StringBuilder csv = new StringBuilder("label,scenario,repeat,cart_items,statements_prepared,statements_captured,entity_loads,collection_loads\n");
        StringBuilder sqlLog = new StringBuilder();

        for (int r = 0; r < REPEATS; r++) {
            measureOnce(readOnly, stats, csv, sqlLog, "getCart", r,
                    () -> cartService.getCart(buyerEmail, cartId));
            measureOnce(readOnly, stats, csv, sqlLog, "getAllProducts", r,
                    () -> productService.getAllProducts(0, ITEMS, "productId", "asc", "NPlus " + prefix, null));
        }

        write("query_count_" + LABEL + ".csv", csv.toString());
        write("query_count_" + LABEL + "_sql.txt", sqlLog.toString());
    }

    private void measureOnce(TransactionTemplate readOnly, Statistics stats, StringBuilder csv, StringBuilder sqlLog,
                             String scenario, int repeat, Runnable call) {
        stats.clear();
        SqlCapture.clear();
        readOnly.executeWithoutResult(status -> call.run());
        List<String> sql = SqlCapture.snapshot();
        csv.append(String.join(",", LABEL, scenario, String.valueOf(repeat), String.valueOf(ITEMS),
                String.valueOf(stats.getPrepareStatementCount()), String.valueOf(sql.size()),
                String.valueOf(stats.getEntityLoadCount()), String.valueOf(stats.getCollectionLoadCount()))).append('\n');
        if (repeat == 0) {
            sqlLog.append("### ").append(scenario).append(" (").append(sql.size()).append(" statements)\n");
            for (int i = 0; i < sql.size(); i++) {
                sqlLog.append(i + 1).append(". ").append(sql.get(i)).append('\n');
            }
            sqlLog.append('\n');
        }
    }
}
