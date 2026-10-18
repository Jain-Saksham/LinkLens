package com.linklens.ratelimit;

import com.linklens.config.RateLimitProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One in-memory token bucket per client key (the client IP).
 *
 * <p>Scope: limits are per application instance. With N instances a client could get up to N times
 * the limit; at that point the buckets would move to Redis (an atomic Lua script per request).
 * In-memory is chosen here because it adds no network hop and keeps working when Redis is down.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;
    private final LongSupplier nanoClock;

    @Autowired
    public RateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    RateLimiter(RateLimitProperties properties, LongSupplier nanoClock) {
        this.properties = properties;
        this.nanoClock = nanoClock;
    }

    public Decision tryAcquire(String clientKey) {
        long now = nanoClock.getAsLong();
        // computeIfAbsent is atomic, so two first requests from one client share a single bucket.
        TokenBucket bucket = buckets.computeIfAbsent(clientKey,
                k -> new TokenBucket(properties.capacity(), properties.refillPeriod().toNanos(), now));
        int remaining = bucket.tryConsume(now);
        if (remaining >= 0) {
            return new Decision(true, properties.capacity(), remaining, 0);
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(bucket.nanosUntilNextToken(now) + 999_999_999));
        return new Decision(false, properties.capacity(), 0, retryAfter);
    }

    /**
     * Bounds memory: buckets that have refilled completely carry no state worth keeping. A request
     * racing with removal may consume from the just-removed bucket and the client's next request gets
     * a fresh, full one: at worst one extra request is allowed, which is acceptable here.
     */
    @Scheduled(fixedDelayString = "PT1M")
    public void evictIdleBuckets() {
        long now = nanoClock.getAsLong();
        buckets.values().removeIf(bucket -> bucket.isFull(now));
    }

    int trackedClients() {
        return buckets.size();
    }
}
