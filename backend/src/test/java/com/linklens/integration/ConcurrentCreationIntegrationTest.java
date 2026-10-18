package com.linklens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.dto.ShortUrlResponse;
import com.linklens.support.IntegrationTest;
import com.linklens.support.TestLinks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class ConcurrentCreationIntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sameAliasRequestedConcurrentlyIsCreatedExactlyOnce() throws Exception {
        String alias = TestLinks.uniqueAlias("race");
        List<HttpStatusCode> statuses = runConcurrently(() -> rest.postForEntity("/api/v1/urls",
                Map.of("url", "https://example.com", "customAlias", alias), String.class).getStatusCode());

        assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(THREADS - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM short_urls WHERE short_code = ?", Long.class, alias))
                .isEqualTo(1L);
    }

    @Test
    void concurrentGeneratedCodesAreAllCreatedAndUnique() throws Exception {
        List<ResponseEntity<ShortUrlResponse>> responses = runConcurrently(() ->
                rest.postForEntity("/api/v1/urls", Map.of("url", "https://example.com/many"), ShortUrlResponse.class));

        Set<String> codes = new HashSet<>();
        responses.forEach(r -> {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            codes.add(r.getBody().shortCode());
        });
        assertThat(codes).hasSize(THREADS);
    }

    /** Releases all threads at once through a latch to maximise overlap. */
    private <T> List<T> runConcurrently(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
