package com.linklens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.support.PostgresTestConfig;
import com.linklens.support.TestLinks;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Redis is pointed at a port nobody listens on: the app must keep working from PostgreSQL alone. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rate-limit.enabled=false", "app.health.enabled=false", "spring.data.redis.url=redis://localhost:1"})
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class RedisUnavailableIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void createAndRedirectStillWorkWithoutRedis() {
        String alias = TestLinks.uniqueAlias("nocache");
        ResponseEntity<String> created = rest.postForEntity(
                "/api/v1/urls", Map.of("url", "https://example.com/fallback", "customAlias", alias), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        TestRestTemplate noRedirects = TestLinks.noRedirects(rest);
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            ResponseEntity<Void> redirect = noRedirects.getForEntity("/" + alias, Void.class);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/fallback");
            // After the first failure the cache is bypassed, so no request pays a Redis timeout.
            assertThat(took).isLessThan(Duration.ofSeconds(1));
        }

        assertThat(noRedirects.getForEntity("/" + TestLinks.uniqueAlias("missing"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
