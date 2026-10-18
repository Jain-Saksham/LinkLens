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
 * <p>Rate limiting is off here (all requests come from 127.0.0.1); it has its own tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.enabled=false")
@Import({PostgresTestConfig.class, RedisTestConfig.class})
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {
}
