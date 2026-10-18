package com.linklens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.dto.ShortUrlResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings.Redirects;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full stack against a real PostgreSQL: Flyway migration, JPA mapping, unique constraint and the
 * atomic click update. Skipped automatically when Docker isn't available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ShortUrlFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void createRedirectAndCountClicks() {
        ResponseEntity<ShortUrlResponse> created = rest.postForEntity(
                "/api/v1/urls", Map.of("url", "https://example.com/page", "customAlias", "it-flow"),
                ShortUrlResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Don't follow redirects, so the 302 itself is observable (and example.com is never called).
        TestRestTemplate noRedirects = rest.withRedirects(Redirects.DONT_FOLLOW);
        ResponseEntity<Void> redirect = noRedirects.getForEntity("/it-flow", Void.class);
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/page");
        noRedirects.getForEntity("/it-flow", Void.class);

        ShortUrlResponse details = rest.getForObject("/api/v1/urls/it-flow", ShortUrlResponse.class);
        assertThat(details.clickCount()).isEqualTo(2);

        ResponseEntity<String> duplicate = rest.postForEntity(
                "/api/v1/urls", Map.of("url", "https://example.com/other", "customAlias", "it-flow"), String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
