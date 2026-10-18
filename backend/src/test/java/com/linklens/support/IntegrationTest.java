package com.linklens.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full application on a random port against real PostgreSQL + Redis containers. Every class using
 * this annotation shares one Spring context (identical configuration), so containers start once.
 * Skipped automatically when Docker isn't available.
 *
 * <p>Rate limiting is off here (all requests come from 127.0.0.1); it has its own tests. The health
 * scheduler is off (no background calls to real sites), and loopback targets are allowed so health
 * tests can point links at a local HTTP server. Never enable allow-loopback outside tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rate-limit.enabled=false", "app.health.enabled=false", "app.health.allow-loopback=true"})
@Import({PostgresTestConfig.class, RedisTestConfig.class})
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {
}
