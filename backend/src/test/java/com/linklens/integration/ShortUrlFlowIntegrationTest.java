package com.linklens.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.linklens.dto.ShortUrlResponse;
import com.linklens.support.IntegrationTest;
import com.linklens.support.TestLinks;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The happy path end to end: create, redirect, count. */
@IntegrationTest
class ShortUrlFlowIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void createRedirectAndCountClicks() {
        String alias = TestLinks.uniqueAlias("flow");
        ResponseEntity<ShortUrlResponse> created = rest.postForEntity(
                "/api/v1/urls", Map.of("url", "https://example.com/page", "customAlias", alias),
                ShortUrlResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        TestRestTemplate noRedirects = TestLinks.noRedirects(rest);
        ResponseEntity<Void> redirect = noRedirects.getForEntity("/" + alias, Void.class);
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/page");
        noRedirects.getForEntity("/" + alias, Void.class);

        // Clicks are recorded asynchronously, off the redirect's request thread.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(rest.getForObject("/api/v1/urls/" + alias, ShortUrlResponse.class).clickCount())
                        .isEqualTo(2));
    }
}
