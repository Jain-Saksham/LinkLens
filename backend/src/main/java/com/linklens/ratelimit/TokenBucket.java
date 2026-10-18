package com.linklens.ratelimit;

/**
 * Classic token bucket: holds up to {@code capacity} tokens, each request takes one, and tokens
 * trickle back at a steady rate. That allows short bursts while capping the sustained rate.
 *
 * <p>Refill is computed lazily from elapsed time on each call, so there is no background thread.
 * Methods are {@code synchronized} on the bucket: the read-refill-decrement sequence must be atomic or
 * two concurrent requests could both see the last token. Contention is per client, not global,
 * because each client has its own bucket.
 */
final class TokenBucket {

    private final int capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(int capacity, long refillPeriodNanos, long nowNanos) {
        this.capacity = capacity;
        this.tokensPerNano = (double) capacity / refillPeriodNanos;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    /** @return remaining whole tokens after consuming one, or -1 if the request must be rejected */
    synchronized int tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens < 1) {
            return -1;
        }
        tokens -= 1;
        return (int) tokens;
    }

    /** Nanoseconds until one whole token is available again (0 if one already is). */
    synchronized long nanosUntilNextToken(long nowNanos) {
        refill(nowNanos);
        return tokens >= 1 ? 0 : (long) Math.ceil((1 - tokens) / tokensPerNano);
    }

    /** A full bucket that has been idle is indistinguishable from a new one, so it can be dropped. */
    synchronized boolean isFull(long nowNanos) {
        refill(nowNanos);
        return tokens >= capacity;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastRefillNanos = nowNanos;
        }
    }
}
