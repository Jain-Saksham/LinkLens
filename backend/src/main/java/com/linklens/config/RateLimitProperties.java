package com.linklens.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Token bucket per client: up to {@code capacity} requests in a burst, refilled continuously at
 * {@code capacity} tokens per {@code refillPeriod} (default: 20 links per minute).
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") @Min(1) int capacity,
        @DefaultValue("1m") Duration refillPeriod) {
}
