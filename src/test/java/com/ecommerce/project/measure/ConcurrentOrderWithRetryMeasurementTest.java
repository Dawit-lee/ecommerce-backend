package com.ecommerce.project.measure;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

@EnabledIfSystemProperty(named = "measure", matches = "true")
@TestPropertySource(properties = "order.stock.max-attempts=3")
class ConcurrentOrderWithRetryMeasurementTest extends ConcurrentOrderMeasurement {
}
