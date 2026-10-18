package com.linklens.integration;

import static com.linklens.support.TestLinks.CACHE_KEY_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.linklens.support.IntegrationTest;
import com.linklens.support.TestLinks;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class RedirectCacheIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    private TestRestTemplate noRedirects;

    @BeforeEach
    void setUp() {
        noRedirects = TestLinks.noRedirects(rest);
    }

    @Test
    void firstRedirectPopulatesCacheAndLaterRedirectsAreServedFromIt() {
        String alias = create("https://example.com/original", null);
        redis.delete(CACHE_KEY_PREFIX + alias); // start cold (creation writes through)

        assertRedirect(alias, "https://example.com/original");
        Long ttl = redis.getExpire(CACHE_KEY_PREFIX + alias, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(3500L, 3600L);

        // Change the row behind the cache's back: the redirect still uses the cached value,
        // which proves the database wasn't consulted.
        jdbc.update("UPDATE short_urls SET original_url = ? WHERE short_code = ?", "https://example.com/changed", alias);
        assertRedirect(alias, "https://example.com/original");

        redis.delete(CACHE_KEY_PREFIX + alias);
        assertRedirect(alias, "https://example.com/changed");
    }

    @Test
    void cacheTtlNeverOutlivesTheLink() {
        String alias = create("https://example.com", Instant.now().plusSeconds(30));
        Long ttl = redis.getExpire(CACHE_KEY_PREFIX + alias, TimeUnit.SECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(30L);
    }

    @Test
    void cachedLinkStopsRedirectingTheMomentItExpires() {
        String alias = create("https://example.com", Instant.now().plusSeconds(2));
        assertRedirect(alias, "https://example.com"); // served and cached while valid

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(noRedirects.getForEntity("/" + alias, String.class).getStatusCode())
                        .isEqualTo(HttpStatus.GONE));
    }

    @Test
    void expiredLinkInDatabaseReturns410AndIsNotCached() {
        String alias = TestLinks.uniqueAlias("old");
        jdbc.update("INSERT INTO short_urls (short_code, original_url, expires_at) VALUES (?, ?, now() - interval '1 hour')",
                alias, "https://example.com");

        assertThat(noRedirects.getForEntity("/" + alias, String.class).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(redis.hasKey(CACHE_KEY_PREFIX + alias)).isFalse();
    }

    @Test
    void unknownCodeIsNegativelyCachedUntilSomeoneCreatesIt() {
        String alias = TestLinks.uniqueAlias("later");

        assertThat(noRedirects.getForEntity("/" + alias, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(redis.getExpire(CACHE_KEY_PREFIX + alias, TimeUnit.SECONDS)).isBetween(1L, 60L);

        // Creating the alias overwrites the negative entry (write-through), so it works immediately.
        rest.postForEntity("/api/v1/urls", Map.of("url", "https://example.com/now", "customAlias", alias), String.class);
        assertRedirect(alias, "https://example.com/now");
    }

    private String create(String url, Instant expiresAt) {
        String alias = TestLinks.uniqueAlias("cache");
        Map<String, Object> body = expiresAt == null
                ? Map.of("url", url, "customAlias", alias)
                : Map.of("url", url, "customAlias", alias, "expiresAt", expiresAt.toString());
        assertThat(rest.postForEntity("/api/v1/urls", body, String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return alias;
    }

    private void assertRedirect(String alias, String expectedLocation) {
        ResponseEntity<Void> response = noRedirects.getForEntity("/" + alias, Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString(expectedLocation);
    }
}
