package com.ecommerce.project.measure;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared PostgreSQL container (same image as CI) and test-only properties for the measurement tests.
 * Measurement tests only run with -Dmeasure=true so the normal build is not affected.
 */
public abstract class MeasurementSupport {

    static final String POSTGRES_IMAGE = "postgres:16";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);
    static final Path OUT_DIR = Path.of("docs", "measurements", "raw");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Test-only values; StripeService is mocked in every measurement test.
        registry.add("spring.app.jwtSecret", () -> "bWVhc3VyZW1lbnQtb25seS1zZWNyZXQtbWVhc3VyZW1lbnQtb25seS1zZWNyZXQtMDEyMzQ1Njc4OQ==");
        registry.add("stripe.secret.key", () -> "sk_test_measurement_dummy");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        // -Dmeasure.inspector lets the no-lock baseline swap in NoVersionCheckInspector (test-only).
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                () -> System.getProperty("measure.inspector", SqlCapture.class.getName()));
        registry.add("logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener", () -> "OFF");
    }

    static void write(String fileName, String content) throws IOException {
        Files.createDirectories(OUT_DIR);
        Files.writeString(OUT_DIR.resolve(fileName), content);
    }
}
