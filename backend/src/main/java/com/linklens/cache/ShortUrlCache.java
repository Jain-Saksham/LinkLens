package com.linklens.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklens.config.CacheProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside store for short-code lookups.
 *
 * <ul>
 *   <li><b>TTL</b>: {@code min(configured ttl, time until the link expires)}, so an entry never
 *       outlives its link. Expiry is still checked on every hit; the TTL only bounds memory/staleness.</li>
 *   <li><b>Negative caching</b>: unknown codes are cached briefly to absorb scans/typos.</li>
 *   <li><b>Invalidation</b>: links are immutable, so the only write is creation, which overwrites
 *       any negative entry for that code (write-through).</li>
 *   <li><b>Fail-open</b>: any Redis error is logged, counted and treated as a miss. Redis is then
 *       bypassed for {@code failureBackoff} so a dead Redis doesn't add a timeout to every request.</li>
 * </ul>
 */
@Component
public class ShortUrlCache {

    private static final Logger log = LoggerFactory.getLogger(ShortUrlCache.class);
    /** Versioned so a change to the JSON shape can't be misread by a newer/older deployment. */
    static final String KEY_PREFIX = "linklens:v1:link:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CacheProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    private volatile Instant bypassUntil = Instant.MIN;

    public ShortUrlCache(StringRedisTemplate redis, ObjectMapper mapper, CacheProperties properties,
                         Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /** Empty means "not cached (or cache unavailable)": the caller must go to the database. */
    public Optional<CachedLink> get(String shortCode) {
        if (!usable()) {
            return Optional.empty();
        }
        String json;
        try {
            json = redis.opsForValue().get(key(shortCode));
        } catch (RuntimeException e) {
            onFailure("get", e);
            return Optional.empty();
        }
        if (json == null) {
            count("miss");
            return Optional.empty();
        }
        try {
            count("hit");
            return Optional.of(mapper.readValue(json, CachedLink.class));
        } catch (JsonProcessingException e) {
            log.warn("Discarding unreadable cache entry for '{}'", shortCode);
            count("miss");
            return Optional.empty();
        }
    }

    public void put(String shortCode, CachedLink link) {
        if (!usable()) {
            return;
        }
        Duration ttl = ttlFor(link);
        if (ttl.isZero() || ttl.isNegative()) {
            return; // already expired: nothing worth caching
        }
        try {
            redis.opsForValue().set(key(shortCode), mapper.writeValueAsString(link), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CachedLink is always serializable", e);
        } catch (RuntimeException e) {
            onFailure("put", e);
        }
    }

    Duration ttlFor(CachedLink link) {
        if (link.isNotFound()) {
            return properties.negativeTtl();
        }
        if (link.expiresAt() == null) {
            return properties.ttl();
        }
        Duration untilExpiry = Duration.between(Instant.now(clock), link.expiresAt());
        return untilExpiry.compareTo(properties.ttl()) < 0 ? untilExpiry : properties.ttl();
    }

    private boolean usable() {
        return properties.enabled() && !Instant.now(clock).isBefore(bypassUntil);
    }

    private void onFailure(String operation, RuntimeException e) {
        count("error");
        bypassUntil = Instant.now(clock).plus(properties.failureBackoff());
        log.warn("Redis {} failed ({}); bypassing cache for {}", operation, e.getClass().getSimpleName(),
                properties.failureBackoff());
    }

    private void count(String result) {
        meters.counter("linklens.cache", "result", result).increment();
    }

    static String key(String shortCode) {
        return KEY_PREFIX + shortCode;
    }
}
