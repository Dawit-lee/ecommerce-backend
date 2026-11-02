package com.ecommerce.project.measure;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

/**
 * No-lock baseline: same main code, data, seeds and pool as the a3_pool10 runs, but with the
 * @Version predicate neutralised by NoVersionCheckInspector. Must be run with
 * -Dmeasure.inspector=com.ecommerce.project.measure.NoVersionCheckInspector in its own JVM.
 */
@EnabledIfSystemProperty(named = "measure", matches = "true")
@EnabledIfSystemProperty(named = "measure.inspector", matches = ".*NoVersionCheckInspector")
@TestPropertySource(properties = {"order.stock.max-attempts=3", "spring.datasource.hikari.maximum-pool-size=10"})
class DistributedNoLockPool10MeasurementTest extends DistributedOrderMeasurement {

    @Override
    String configLabel(int poolSize) {
        return "nolock_pool" + poolSize;
    }
}
