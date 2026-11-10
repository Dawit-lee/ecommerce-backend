package com.ecommerce.project.measure;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

/** Pessimistic lock (SELECT ... FOR UPDATE, product-id order); same data, seeds and pool as a3_pool10. */
@EnabledIfSystemProperty(named = "measure", matches = "true")
@TestPropertySource(properties = {"order.stock.lock=pessimistic", "order.stock.max-attempts=3",
        "spring.datasource.hikari.maximum-pool-size=10"})
class DistributedPessimisticPool10MeasurementTest extends DistributedOrderMeasurement {

    @Override
    String configLabel(int poolSize) {
        return "pessimistic_pool" + poolSize;
    }
}
