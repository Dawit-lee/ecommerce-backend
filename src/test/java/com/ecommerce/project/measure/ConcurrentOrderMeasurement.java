package com.ecommerce.project.measure;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.model.*;
import com.ecommerce.project.repositories.*;
import com.ecommerce.project.service.OrderService;
import com.ecommerce.project.service.StripeService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;

import static org.mockito.ArgumentMatchers.anyString;

/**
 * R units of stock on one product, N users each holding that product (qty 1) in their cart,
 * N concurrent placeOrder calls on a fixed pool of T threads. Stripe is mocked.
 * Subclasses pin order.stock.max-attempts so the retry and no-retry runs use separate contexts.
 */
@SpringBootTest
abstract class ConcurrentOrderMeasurement extends MeasurementSupport {

    static final int STOCK = Integer.getInteger("measure.stock", 100);
    static final int ORDERS = Integer.getInteger("measure.orders", 200);
    static final int THREADS = Integer.getInteger("measure.threads", 50);
    static final int RUNS = Integer.getInteger("measure.runs", 5);
    static final int WARMUP_RUNS = Integer.getInteger("measure.warmup", 1);

    @MockitoBean
    StripeService stripeService;

    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;
    @Autowired UserRepository userRepository;
    @Autowired AddressRepository addressRepository;
    @Autowired CartRepository cartRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired DataSource dataSource;

    @Value("${order.stock.max-attempts:3}")
    int maxAttempts;

    record Seed(Long productId, List<String> emails, List<Long> addressIds) {}

    record Outcome(int index, String result, double latencyMs, String error) {}

    @Test
    void measure() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        String tag = "a" + maxAttempts;

        StringBuilder runsCsv = new StringBuilder("run,warmup,stock,orders,threads,max_attempts,success,out_of_stock,retry_exhausted,other,"
                + "final_stock,orders_in_db,ordered_qty,oversell,product_update_statements,hibernate_optimistic_failures,refund_calls,"
                + "wall_ms,avg_ms,p50_ms,p95_ms,p99_ms,max_ms\n");
        StringBuilder requestsCsv = new StringBuilder("run,warmup,request,result,latency_ms,error\n");

        for (int run = 0; run < WARMUP_RUNS + RUNS; run++) {
            boolean warmup = run < WARMUP_RUNS;
            Seed seed = seed(tag + "r" + run);
            Mockito.clearInvocations(stripeService);
            Mockito.when(stripeService.refundPayment(anyString())).thenReturn(null);
            stats.clear();
            SqlCapture.clear();

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < ORDERS; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    long t0 = System.nanoTime();
                    String result;
                    String error = "";
                    try {
                        orderService.placeOrder(seed.emails().get(idx), seed.addressIds().get(idx),
                                "Card", "Stripe", "pi_measure_" + idx, "succeeded", "measurement");
                        result = "SUCCESS";
                    } catch (APIException e) {
                        String msg = String.valueOf(e.getMessage());
                        result = msg.contains("high demand") ? "RETRY_EXHAUSTED"
                                : msg.contains("Not enough stock") ? "OUT_OF_STOCK" : "OTHER";
                        error = msg;
                    } catch (Exception e) {
                        result = "OTHER";
                        error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                    return new Outcome(idx, result, (System.nanoTime() - t0) / 1_000_000.0, error);
                }));
            }
            long wall0 = System.nanoTime();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(5, TimeUnit.MINUTES));
            }
            double wallMs = (System.nanoTime() - wall0) / 1_000_000.0;
            pool.shutdown();

            Map<String, Long> counts = new TreeMap<>();
            outcomes.forEach(o -> counts.merge(o.result(), 1L, Long::sum));
            double[] lat = outcomes.stream().mapToDouble(Outcome::latencyMs).sorted().toArray();

            Integer finalStock = jdbcTemplate.queryForObject(
                    "select quantity from products where product_id = ?", Integer.class, seed.productId());
            Integer ordersInDb = jdbcTemplate.queryForObject(
                    "select count(*) from orders where email like ?", Integer.class, tag + "r" + run + "\\_%");
            Integer orderedQty = jdbcTemplate.queryForObject(
                    "select coalesce(sum(quantity), 0) from order_items where product_id = ?", Integer.class, seed.productId());
            boolean oversell = finalStock < 0 || (STOCK - finalStock) != orderedQty
                    || orderedQty != counts.getOrDefault("SUCCESS", 0L).intValue();
            long productUpdates = SqlCapture.snapshot().stream()
                    .filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("update products")).count();
            long refundCalls = Mockito.mockingDetails(stripeService).getInvocations().stream()
                    .filter(inv -> inv.getMethod().getName().equals("refundPayment")).count();

            runsCsv.append(String.join(",", List.of(
                    String.valueOf(run), String.valueOf(warmup), String.valueOf(STOCK), String.valueOf(ORDERS),
                    String.valueOf(THREADS), String.valueOf(maxAttempts),
                    String.valueOf(counts.getOrDefault("SUCCESS", 0L)), String.valueOf(counts.getOrDefault("OUT_OF_STOCK", 0L)),
                    String.valueOf(counts.getOrDefault("RETRY_EXHAUSTED", 0L)), String.valueOf(counts.getOrDefault("OTHER", 0L)),
                    String.valueOf(finalStock), String.valueOf(ordersInDb), String.valueOf(orderedQty), String.valueOf(oversell),
                    String.valueOf(productUpdates), String.valueOf(stats.getOptimisticFailureCount()), String.valueOf(refundCalls),
                    fmt(wallMs), fmt(Arrays.stream(lat).average().orElse(0)), fmt(pct(lat, 50)), fmt(pct(lat, 95)),
                    fmt(pct(lat, 99)), fmt(lat[lat.length - 1])))).append('\n');
            for (Outcome o : outcomes) {
                requestsCsv.append(run).append(',').append(warmup).append(',').append(o.index()).append(',')
                        .append(o.result()).append(',').append(fmt(o.latencyMs())).append(',')
                        .append('"').append(o.error().replace("\"", "'")).append('"').append('\n');
            }
        }

        String prefix = "concurrency_max_attempts_" + maxAttempts;
        write(prefix + "_runs.csv", runsCsv.toString());
        write(prefix + "_requests.csv", requestsCsv.toString());
        write(prefix + "_env.txt", String.join("\n",
                "postgres_image=" + POSTGRES_IMAGE,
                "postgres_version=" + jdbcTemplate.queryForObject("select version()", String.class),
                "transaction_isolation=" + jdbcTemplate.queryForObject("show transaction_isolation", String.class),
                "hikari_maximum_pool_size=" + dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize(),
                "java=" + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"),
                "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"),
                "available_processors=" + Runtime.getRuntime().availableProcessors(),
                "stock=" + STOCK, "orders=" + ORDERS, "threads=" + THREADS,
                "runs=" + RUNS, "warmup_runs=" + WARMUP_RUNS, "max_attempts=" + maxAttempts, ""));
    }

    private Seed seed(String prefix) {
        return transactionTemplate.execute(status -> {
            Product product = new Product();
            product.setProductName("Measure " + prefix);
            product.setDescription("concurrency measurement product");
            product.setQuantity(STOCK);
            product.setPrice(100);
            product.setSpecialPrice(100);
            product = productRepository.save(product);

            List<String> emails = new ArrayList<>();
            List<Long> addressIds = new ArrayList<>();
            for (int i = 0; i < ORDERS; i++) {
                String name = prefix + "_" + i;
                User user = userRepository.save(new User(name, name + "@measure.test", "measure-password"));

                Address address = new Address("Street 1", "Building 1", "Seoul", "Seoul", "Korea", "12345");
                address.setUser(user);
                addressIds.add(addressRepository.save(address).getAddressId());

                Cart cart = new Cart();
                cart.setUser(user);
                cart.setTotalPrice(100.0);
                CartItem item = new CartItem();
                item.setCart(cart);
                item.setProduct(product);
                item.setQuantity(1);
                item.setProductPrice(100);
                cart.getCartItems().add(item);
                cartRepository.save(cart);
                emails.add(user.getEmail());
            }
            return new Seed(product.getProductId(), emails, addressIds);
        });
    }

    private static double pct(double[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
