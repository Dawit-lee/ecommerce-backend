package com.ecommerce.project.measure;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

@EnabledIfSystemProperty(named = "measure", matches = "true")
@TestPropertySource(properties = {"order.stock.max-attempts=3", "spring.datasource.hikari.maximum-pool-size=20"})
class DistributedA3Pool20MeasurementTest extends DistributedOrderMeasurement {
}
