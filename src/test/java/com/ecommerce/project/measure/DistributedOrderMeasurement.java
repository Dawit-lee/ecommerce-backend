package com.ecommerce.project.measure;

import com.ecommerce.project.exceptions.APIException;
import com.ecommerce.project.model.*;
import com.ecommerce.project.repositories.*;
import com.ecommerce.project.service.OrderService;
import com.ecommerce.project.service.StripeService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.persistence.EntityManagerFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.mockito.ArgumentMatchers.anyString;

/**
 * Distributed-load order scenarios (A–F) for the optimistic-lock + retry order flow.
 * Every scenario: fresh products/users per run, 1 warmup + N measured runs, Stripe mocked.
 * Subclasses pin order.stock.max-attempts and the Hikari pool size (one Spring context each).
 *
 * Per-order attempt counts come from a test-only interceptor added to the existing
 * OrderServiceImpl proxy; main code is not modified.
 */
@SpringBootTest
abstract class DistributedOrderMeasurement extends MeasurementSupport {

    static final int RUNS = Integer.getInteger("measure.runs", 5);
    static final int WARMUP_RUNS = Integer.getInteger("measure.warmup", 1);
    static final List<String> SCENARIOS = List.of(System.getProperty("measure.scenarios", "A,B,C,D,E,F").split(","));
    static final double ZIPF_S = Double.parseDouble(System.getProperty("measure.zipfS", "1.0"));
    static final int PRODUCTS = 50;
    static final int STOCK = 100;
    static final int ORDERS = 200;
    static final int THREADS = 50;
    static final int C_ORDERS = Integer.getInteger("measure.c.orders", 1000);
    static final double C_SECONDS = Double.parseDouble(System.getProperty("measure.c.seconds", "10"));
    static final int[] F_SIZES = Arrays.stream(System.getProperty("measure.f.sizes", "2,5,10,20,30,50").split(","))
            .mapToInt(Integer::parseInt).toArray();

    /** Per-thread log of placeOrderTransactional attempts for the order currently running on that thread. */
    static final ThreadLocal<List<String>> ATTEMPTS = new ThreadLocal<>();

    static final MethodInterceptor ATTEMPT_COUNTER = invocation -> {
        if (!invocation.getMethod().getName().equals("placeOrderTransactional")) {
            return invocation.proceed();
        }
        List<String> log = ATTEMPTS.get();
        try {
            Object result = invocation.proceed();
            if (log != null) log.add("OK");
            return result;
        } catch (Throwable t) {
            if (log != null) log.add(t.getClass().getSimpleName());
            throw t;
        }
    };

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

    /** stocks[p] = initial stock of product p; orders.get(i) = product indexes in order i's cart (qty 1 each). */
    record Spec(String scenario, String name, int seed, int[] stocks, List<int[]> orders, int threads, long[] arrivalNanos) {}

    record Seeded(long[] productIds, List<String> emails, List<Long> addressIds) {}

    record Req(int index, String result, List<String> attempts, double latencyMs, double queueMs, String error) {}

    @BeforeEach
    void installAttemptCounter() {
        if (!(orderService instanceof Advised advised)) {
            throw new IllegalStateException("OrderService bean is not an AOP proxy; cannot count attempts without touching main code");
        }
        boolean installed = false;
        for (Advisor a : advised.getAdvisors()) {
            if (a.getAdvice() == ATTEMPT_COUNTER) installed = true;
        }
        if (!installed) {
            advised.addAdvice(0, ATTEMPT_COUNTER);
        }
    }

    /** First product UPDATE seen in the last run, recorded as evidence of the SQL actually executed. */
    String productUpdateSqlSample = "";

    /** Label used in output file names and generated user names; the no-lock baseline overrides it. */
    String configLabel(int poolSize) {
        return "a" + maxAttempts + "_pool" + poolSize;
    }

    @Test
    void measure() throws Exception {
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        String config = configLabel(poolSize);
        Map<String, StringBuilder> runs = new LinkedHashMap<>();
        Map<String, StringBuilder> requests = new LinkedHashMap<>();
        Map<String, StringBuilder> products = new LinkedHashMap<>();
        long started = System.nanoTime();

        for (String scenario : SCENARIOS) {
            runs.put(scenario, new StringBuilder(RUNS_HEADER));
            requests.put(scenario, new StringBuilder(REQUESTS_HEADER));
            products.put(scenario, new StringBuilder(PRODUCTS_HEADER));
            for (int run = 0; run < WARMUP_RUNS + RUNS; run++) {
                for (Spec spec : specs(scenario, run)) {
                    execute(spec, run, run < WARMUP_RUNS, poolSize, config,
                            runs.get(scenario), requests.get(scenario), products.get(scenario));
                }
            }
            String prefix = "distributed_" + scenario + "_" + config;
            write(prefix + "_runs.csv", runs.get(scenario).toString());
            write(prefix + "_requests.csv", requests.get(scenario).toString());
            write(prefix + "_products.csv", products.get(scenario).toString());
        }

        write("distributed_" + config + "_env.txt", String.join("\n",
                "postgres_image=" + POSTGRES_IMAGE,
                "postgres_version=" + jdbcTemplate.queryForObject("select version()", String.class),
                "transaction_isolation=" + jdbcTemplate.queryForObject("show transaction_isolation", String.class),
                "hikari_maximum_pool_size=" + poolSize,
                "max_attempts=" + maxAttempts,
                "java=" + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"),
                "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"),
                "available_processors=" + Runtime.getRuntime().availableProcessors(),
                "scenarios=" + String.join(",", SCENARIOS),
                "runs=" + RUNS, "warmup_runs=" + WARMUP_RUNS, "seed=run index (0 = warmup)",
                "zipf_s=" + ZIPF_S, "products=" + PRODUCTS, "stock=" + STOCK, "orders=" + ORDERS, "threads=" + THREADS,
                "c_orders=" + C_ORDERS, "c_seconds=" + C_SECONDS, "f_sizes=" + Arrays.toString(F_SIZES),
                "statement_inspector=" + System.getProperty("measure.inspector", SqlCapture.class.getName()),
                "product_update_sql_sample=" + productUpdateSqlSample,
                "total_elapsed_s=" + String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9), ""));
    }

    // ---------------------------------------------------------------- scenarios

    private List<Spec> specs(String scenario, int seed) {
        Random rnd = new Random(seed);
        switch (scenario) {
            case "A": {
                List<int[]> orders = new ArrayList<>();
                for (int i = 0; i < ORDERS; i++) orders.add(new int[]{i % PRODUCTS});
                return List.of(new Spec("A", "A", seed, filled(PRODUCTS, STOCK), orders, THREADS, null));
            }
            case "B": {
                double[] cdf = zipfCdf(PRODUCTS, ZIPF_S);
                List<int[]> orders = new ArrayList<>();
                for (int i = 0; i < ORDERS; i++) orders.add(new int[]{sample(cdf, rnd)});
                return List.of(new Spec("B", "B", seed, filled(PRODUCTS, STOCK), orders, THREADS, null));
            }
            case "C": {
                double[] cdf = zipfCdf(PRODUCTS, ZIPF_S);
                List<int[]> orders = new ArrayList<>();
                long[] arrivals = new long[C_ORDERS];
                double rate = C_ORDERS / C_SECONDS; // orders per second
                double t = 0;
                for (int i = 0; i < C_ORDERS; i++) {
                    orders.add(new int[]{sample(cdf, rnd)});
                    t += -Math.log(1 - rnd.nextDouble()) / rate;
                    arrivals[i] = (long) (t * 1e9);
                }
                return List.of(new Spec("C", "C", seed, filled(PRODUCTS, STOCK), orders, THREADS, arrivals));
            }
            case "D": {
                double[] cdf = zipfCdf(PRODUCTS, ZIPF_S);
                List<int[]> orders = new ArrayList<>();
                for (int i = 0; i < ORDERS; i++) {
                    int k = 1 + rnd.nextInt(3);
                    LinkedHashSet<Integer> picked = new LinkedHashSet<>();
                    while (picked.size() < k) picked.add(sample(cdf, rnd));
                    orders.add(picked.stream().mapToInt(Integer::intValue).toArray());
                }
                return List.of(new Spec("D", "D", seed, filled(PRODUCTS, STOCK), orders, THREADS, null));
            }
            case "E": {
                List<int[]> orders = new ArrayList<>();
                for (int i = 0; i < ORDERS; i++) orders.add(new int[]{0});
                return List.of(new Spec("E", "E", seed, new int[]{STOCK}, orders, THREADS, null));
            }
            case "F": {
                List<Spec> specs = new ArrayList<>();
                for (int n : F_SIZES) {
                    List<int[]> orders = new ArrayList<>();
                    for (int i = 0; i < n; i++) orders.add(new int[]{0});
                    specs.add(new Spec("F", "F_" + n, seed, new int[]{n}, orders, n, null));
                }
                return specs;
            }
            default:
                throw new IllegalArgumentException("Unknown scenario " + scenario);
        }
    }

    static double[] zipfCdf(int n, double s) {
        double[] cdf = new double[n];
        double sum = 0;
        for (int k = 1; k <= n; k++) sum += 1 / Math.pow(k, s);
        double acc = 0;
        for (int k = 1; k <= n; k++) {
            acc += 1 / Math.pow(k, s) / sum;
            cdf[k - 1] = acc;
        }
        cdf[n - 1] = 1.0;
        return cdf;
    }

    static int sample(double[] cdf, Random rnd) {
        double u = rnd.nextDouble();
        int idx = Arrays.binarySearch(cdf, u);
        return idx >= 0 ? idx : -idx - 1;
    }

    static int[] filled(int n, int v) {
        int[] a = new int[n];
        Arrays.fill(a, v);
        return a;
    }

    // ---------------------------------------------------------------- execution

    private void execute(Spec spec, int run, boolean warmup, int poolSize, String config,
                         StringBuilder runsCsv, StringBuilder requestsCsv, StringBuilder productsCsv) throws Exception {
        String prefix = config.replace("_pool", "p") + spec.name().replace("_", "") + "r" + run;
        Seeded seeded = seed(spec, prefix);
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        Mockito.clearInvocations(stripeService);
        Mockito.when(stripeService.refundPayment(anyString())).thenReturn(null);
        stats.clear();
        SqlCapture.clear();

        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        AtomicBoolean sampling = new AtomicBoolean(true);
        long[] hikari = new long[4]; // maxActive, maxPending, sumPending, samples
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                int active = pool.getActiveConnections();
                int pending = pool.getThreadsAwaitingConnection();
                hikari[0] = Math.max(hikari[0], active);
                hikari[1] = Math.max(hikari[1], pending);
                hikari[2] += pending;
                hikari[3]++;
                LockSupport.parkNanos(2_000_000);
            }
        });

        int n = spec.orders().size();
        ExecutorService executor = Executors.newFixedThreadPool(spec.threads());
        CountDownLatch start = new CountDownLatch(1);
        long[] scheduled = new long[n];
        List<Future<Req>> futures = new ArrayList<>(Collections.nCopies(n, null));

        sampler.start();
        long wall0;
        if (spec.arrivalNanos() == null) {
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.set(i, executor.submit(() -> {
                    start.await();
                    return placeOne(seeded, idx, System.nanoTime());
                }));
            }
            wall0 = System.nanoTime();
            start.countDown();
        } else {
            wall0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                long target = wall0 + spec.arrivalNanos()[i];
                long now;
                while ((now = System.nanoTime()) < target) {
                    LockSupport.parkNanos(target - now);
                }
                int idx = i;
                scheduled[i] = target;
                futures.set(i, executor.submit(() -> placeOne(seeded, idx, scheduled[idx])));
            }
        }
        List<Req> reqs = new ArrayList<>();
        for (Future<Req> f : futures) reqs.add(f.get(10, TimeUnit.MINUTES));
        double wallMs = (System.nanoTime() - wall0) / 1e6;
        executor.shutdown();
        sampling.set(false);
        sampler.join();

        // ---- DB verification
        int p = spec.stocks().length;
        int[] finalStock = new int[p];
        int[] orderedQty = new int[p];
        int[] assigned = new int[p];
        for (int i = 0; i < p; i++) {
            finalStock[i] = jdbcTemplate.queryForObject("select quantity from products where product_id = ?", Integer.class, seeded.productIds()[i]);
            orderedQty[i] = jdbcTemplate.queryForObject("select coalesce(sum(quantity), 0) from order_items where product_id = ?", Integer.class, seeded.productIds()[i]);
        }
        for (int[] items : spec.orders()) for (int item : items) assigned[item]++;
        Integer ordersInDb = jdbcTemplate.queryForObject("select count(*) from orders where email like ?", Integer.class, prefix + "\\_%");

        int oversoldProducts = 0;
        int negativeStockProducts = 0;
        int decrementMismatchProducts = 0;
        int decrementedTotal = 0;
        int lostDecrements = 0;   // sold (order_items) minus actually decremented stock
        int oversoldUnits = 0;    // sold beyond the initial stock
        for (int i = 0; i < p; i++) {
            if (finalStock[i] < 0 || spec.stocks()[i] - finalStock[i] != orderedQty[i]) oversoldProducts++;
            if (finalStock[i] < 0) negativeStockProducts++;
            if (spec.stocks()[i] - finalStock[i] != orderedQty[i]) decrementMismatchProducts++;
            decrementedTotal += spec.stocks()[i] - finalStock[i];
            lostDecrements += orderedQty[i] - (spec.stocks()[i] - finalStock[i]);
            oversoldUnits += Math.max(0, orderedQty[i] - spec.stocks()[i]);
        }

        Map<String, Integer> counts = new TreeMap<>();
        int[] successByAttempt = new int[5]; // index = attempts used; 4 = more than 3
        int exhaustedWithStock = 0;
        long totalAttempts = 0;
        int successItems = 0;
        int[][] perProduct = new int[p][5]; // success, exhausted, exhaustedWithStock, outOfStock, other
        for (Req r : reqs) {
            counts.merge(r.result(), 1, Integer::sum);
            totalAttempts += r.attempts().size();
            int[] items = spec.orders().get(r.index());
            boolean stockLeft = Arrays.stream(items).allMatch(it -> finalStock[it] >= 1);
            if (r.result().equals("SUCCESS")) {
                successByAttempt[Math.min(r.attempts().size(), 4)]++;
                successItems += items.length;
            }
            if (r.result().equals("RETRY_EXHAUSTED") && stockLeft) exhaustedWithStock++;
            for (int it : items) {
                switch (r.result()) {
                    case "SUCCESS" -> perProduct[it][0]++;
                    case "RETRY_EXHAUSTED" -> {
                        perProduct[it][1]++;
                        if (stockLeft) perProduct[it][2]++;
                    }
                    case "OUT_OF_STOCK" -> perProduct[it][3]++;
                    default -> perProduct[it][4]++;
                }
            }
        }
        int orderedTotal = Arrays.stream(orderedQty).sum();
        int success = counts.getOrDefault("SUCCESS", 0);
        long refundCalls = Mockito.mockingDetails(stripeService).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("refundPayment")).count();

        SqlCapture.snapshot().stream().filter(sql -> sql.startsWith("update products ")).findFirst()
                .ifPresent(sql -> productUpdateSqlSample = sql);
        SqlCapture.clear();

        double[] lat = reqs.stream().mapToDouble(Req::latencyMs).sorted().toArray();
        double[] queue = reqs.stream().mapToDouble(Req::queueMs).sorted().toArray();

        runsCsv.append(csv(spec.name(), run, warmup, spec.seed(), maxAttempts, poolSize, p, n,
                spec.orders().stream().mapToInt(o -> o.length).sum(), spec.threads(),
                spec.arrivalNanos() == null ? "simultaneous" : "poisson",
                success, counts.getOrDefault("OUT_OF_STOCK", 0), counts.getOrDefault("RETRY_EXHAUSTED", 0),
                counts.getOrDefault("OTHER_DEADLOCK", 0), counts.getOrDefault("OTHER", 0),
                exhaustedWithStock, oversoldProducts, ordersInDb, ordersInDb == success, orderedTotal == successItems,
                refundCalls, stats.getOptimisticFailureCount(),
                successByAttempt[1], successByAttempt[2], successByAttempt[3], successByAttempt[4], totalAttempts,
                fmt(wallMs), fmt(mean(lat)), fmt(pct(lat, 50)), fmt(pct(lat, 95)), fmt(pct(lat, 99)), fmt(lat[lat.length - 1]),
                fmt(mean(queue)), fmt(pct(queue, 95)),
                hikari[0], hikari[1], fmt(hikari[3] == 0 ? 0 : (double) hikari[2] / hikari[3]),
                orderedTotal, successItems, decrementedTotal, lostDecrements, oversoldUnits,
                negativeStockProducts, decrementMismatchProducts)).append('\n');

        for (Req r : reqs) {
            int[] items = spec.orders().get(r.index());
            boolean stockLeft = Arrays.stream(items).allMatch(it -> finalStock[it] >= 1);
            requestsCsv.append(csv(spec.name(), run, warmup, r.index(),
                    String.join(";", Arrays.stream(items).mapToObj(String::valueOf).toList()),
                    r.attempts().size(), String.join(";", r.attempts()), r.result(),
                    fmt(r.latencyMs()), fmt(r.queueMs()), stockLeft, r.error())).append('\n');
        }
        for (int i = 0; i < p; i++) {
            productsCsv.append(csv(spec.name(), run, warmup, i, spec.stocks()[i], assigned[i], orderedQty[i], finalStock[i],
                    !(finalStock[i] < 0 || spec.stocks()[i] - finalStock[i] != orderedQty[i]),
                    perProduct[i][0], perProduct[i][1], perProduct[i][2], perProduct[i][3], perProduct[i][4],
                    spec.stocks()[i] - finalStock[i], orderedQty[i] - (spec.stocks()[i] - finalStock[i]),
                    Math.max(0, orderedQty[i] - spec.stocks()[i]))).append('\n');
        }
    }

    private Req placeOne(Seeded seeded, int idx, long scheduledNanos) {
        long t0 = System.nanoTime();
        List<String> attempts = new ArrayList<>();
        ATTEMPTS.set(attempts);
        String result;
        String error = "";
        try {
            orderService.placeOrder(seeded.emails().get(idx), seeded.addressIds().get(idx),
                    "Card", "Stripe", "pi_dist_" + idx, "succeeded", "measurement");
            result = "SUCCESS";
        } catch (Exception e) {
            result = classify(e);
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            error = root.getClass().getSimpleName() + ": " + root.getMessage();
        } finally {
            ATTEMPTS.remove();
        }
        double latency = (System.nanoTime() - t0) / 1e6;
        return new Req(idx, result, attempts, latency, (t0 - scheduledNanos) / 1e6, error);
    }

    static String classify(Exception e) {
        if (e instanceof APIException) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("high demand")) return "RETRY_EXHAUSTED";
            if (msg.contains("Not enough stock")) return "OUT_OF_STOCK";
        }
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (String.valueOf(c.getMessage()).toLowerCase(Locale.ROOT).contains("deadlock")) return "OTHER_DEADLOCK";
        }
        return "OTHER";
    }

    private Seeded seed(Spec spec, String prefix) {
        return transactionTemplate.execute(status -> {
            long[] productIds = new long[spec.stocks().length];
            Product[] products = new Product[spec.stocks().length];
            for (int i = 0; i < spec.stocks().length; i++) {
                Product product = new Product();
                product.setProductName("Dist " + prefix + " " + i);
                product.setDescription("distributed measurement product");
                product.setQuantity(spec.stocks()[i]);
                product.setPrice(100);
                product.setSpecialPrice(100);
                products[i] = productRepository.save(product);
                productIds[i] = products[i].getProductId();
            }
            List<String> emails = new ArrayList<>();
            List<Long> addressIds = new ArrayList<>();
            for (int i = 0; i < spec.orders().size(); i++) {
                String name = prefix + "_" + i;
                User user = userRepository.save(new User(name, name + "@m.test", "measure-password"));

                Address address = new Address("Street 1", "Building 1", "Seoul", "Seoul", "Korea", "12345");
                address.setUser(user);
                addressIds.add(addressRepository.save(address).getAddressId());

                Cart cart = new Cart();
                cart.setUser(user);
                int[] items = spec.orders().get(i);
                for (int it : items) {
                    CartItem item = new CartItem();
                    item.setCart(cart);
                    item.setProduct(products[it]);
                    item.setQuantity(1);
                    item.setProductPrice(100);
                    cart.getCartItems().add(item);
                }
                cart.setTotalPrice(100.0 * items.length);
                cartRepository.save(cart);
                emails.add(user.getEmail());
            }
            return new Seeded(productIds, emails, addressIds);
        });
    }

    // ---------------------------------------------------------------- csv helpers

    static final String RUNS_HEADER = "scenario,run,warmup,seed,max_attempts,pool_size,products,orders,order_items,threads,arrival,"
            + "success,out_of_stock,retry_exhausted,other_deadlock,other,exhausted_with_stock_left,oversold_products,"
            + "orders_in_db,orders_in_db_eq_success,ordered_qty_eq_success_items,refund_calls,optimistic_failures,"
            + "success_attempt1,success_attempt2,success_attempt3,success_attempt_over3,total_attempts,"
            + "wall_ms,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,queue_avg_ms,queue_p95_ms,"
            + "hikari_max_active,hikari_max_pending,hikari_avg_pending,"
            + "sold_qty,success_items,decremented_qty,lost_decrements,oversold_units,negative_stock_products,decrement_mismatch_products\n";
    static final String REQUESTS_HEADER = "scenario,run,warmup,order,products,attempts,attempt_log,result,latency_ms,queue_ms,stock_left_for_all_items,error\n";
    static final String PRODUCTS_HEADER = "scenario,run,warmup,product_rank,initial_stock,assigned_qty,ordered_qty_db,final_stock,consistent,"
            + "orders_success,orders_exhausted,orders_exhausted_with_stock_left,orders_out_of_stock,orders_other,"
            + "decremented_qty,lost_decrements,oversold_units\n";

    static String csv(Object... values) {
        StringJoiner j = new StringJoiner(",");
        for (Object v : values) {
            String s = String.valueOf(v);
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                s = "\"" + s.replace("\"", "'").replace("\n", " ") + "\"";
            }
            j.add(s);
        }
        return j.toString();
    }

    static double mean(double[] v) {
        return Arrays.stream(v).average().orElse(0);
    }

    static double pct(double[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
