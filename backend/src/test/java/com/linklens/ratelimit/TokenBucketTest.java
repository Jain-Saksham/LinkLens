package com.linklens.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

    @Test
    void allowsABurstUpToCapacityThenRejects() {
        TokenBucket bucket = new TokenBucket(3, MINUTE, 0);

        assertThat(bucket.tryConsume(0)).isEqualTo(2);
        assertThat(bucket.tryConsume(0)).isEqualTo(1);
        assertThat(bucket.tryConsume(0)).isZero();
        assertThat(bucket.tryConsume(0)).isEqualTo(-1);
    }

    @Test
    void refillsContinuouslyAtCapacityPerPeriod() {
        TokenBucket bucket = new TokenBucket(6, MINUTE, 0); // one token every 10s
        for (int i = 0; i < 6; i++) {
            bucket.tryConsume(0);
        }

        assertThat(bucket.nanosUntilNextToken(0)).isEqualTo(TimeUnit.SECONDS.toNanos(10));
        assertThat(bucket.tryConsume(TimeUnit.SECONDS.toNanos(9))).isEqualTo(-1);
        assertThat(bucket.tryConsume(TimeUnit.SECONDS.toNanos(10))).isZero();
    }

    @Test
    void neverRefillsBeyondCapacity() {
        TokenBucket bucket = new TokenBucket(2, MINUTE, 0);

        assertThat(bucket.isFull(10 * MINUTE)).isTrue();
        assertThat(bucket.tryConsume(10 * MINUTE)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsNeverOverspendTheBucket() throws Exception {
        int capacity = 10;
        int threads = 64;
        TokenBucket bucket = new TokenBucket(capacity, MINUTE, 0);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                if (bucket.tryConsume(0) >= 0) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed.get()).isEqualTo(capacity);
    }
}
