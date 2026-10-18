package com.linklens.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Token bucket per client: up to {@code capacity} requests in a burst, refilled continuously at
 * {@code capacity} tokens per {@code refillPeriod} (default: 20 links per minute).
 *
 * @param clientIpHeader header that carries the real client IP, set by a trusted edge that overwrites
 *                       any client-supplied value (Cloudflare's {@code CF-Connecting-IP}). Empty = use the
 *                       connection's remote address. Only set it when every request passes through that
 *                       edge, otherwise clients can choose their own key.
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") @Min(1) int capacity,
        @DefaultValue("1m") Duration refillPeriod,
        @DefaultValue("") String clientIpHeader) {
}
