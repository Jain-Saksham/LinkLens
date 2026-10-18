package com.linklens.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param ttl            upper bound for a cached link (further capped by the link's own expiry)
 * @param negativeTtl    how long an unknown code is remembered as "not found"
 * @param failureBackoff after a Redis error, how long to bypass Redis before trying again
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1h") Duration ttl,
        @DefaultValue("60s") Duration negativeTtl,
        @DefaultValue("30s") Duration failureBackoff) {
}
