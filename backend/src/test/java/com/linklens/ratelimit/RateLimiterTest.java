package com.linklens.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.config.RateLimitProperties;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong();
    private final RateLimiter limiter =
            new RateLimiter(new RateLimitProperties(true, 2, Duration.ofMinutes(1), ""), now::get);

    @Test
    void limitsEachClientIndependently() {
        assertThat(limiter.tryAcquire("1.1.1.1").allowed()).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1").allowed()).isTrue();
        RateLimiter.Decision rejected = limiter.tryAcquire("1.1.1.1");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(30); // 2 per minute -> one token every 30s
        assertThat(limiter.tryAcquire("2.2.2.2").allowed()).isTrue();
    }

    @Test
    void reportsRemainingTokens() {
        RateLimiter.Decision first = limiter.tryAcquire("1.1.1.1");
        assertThat(first.limit()).isEqualTo(2);
        assertThat(first.remaining()).isEqualTo(1);
    }

    @Test
    void retryAfterIsRoundedUpAndAtLeastOneSecond() {
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        now.set(TimeUnit.MILLISECONDS.toNanos(29_500)); // 0.5s before the next token

        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void evictsOnlyBucketsThatHaveFullyRefilled() {
        limiter.tryAcquire("busy");
        limiter.tryAcquire("busy");
        limiter.tryAcquire("idle");
        now.set(TimeUnit.SECONDS.toNanos(30)); // "idle" is full again, "busy" is not

        limiter.evictIdleBuckets();
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }
}
