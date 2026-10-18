package com.linklens.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL for integration tests (H2 would not run our native SQL, Flyway scripts or
 * constraint names faithfully). Static, so every test context shares one container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return POSTGRES;
    }
}
