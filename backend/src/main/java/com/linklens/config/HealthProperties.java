package com.linklens.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Link-rot monitoring and destination inspection.
 *
 * @param enabled          run the background checker (the preview page and manual checks work regardless)
 * @param pollInterval     how often the scheduler looks for due links
 * @param batchSize        links claimed per poll
 * @param concurrency      parallel outbound checks
 * @param healthyInterval  re-check period for a healthy link
 * @param retryBase        first retry delay after a failure; doubles per consecutive failure
 * @param retryMax         cap for the retry delay
 * @param failureThreshold consecutive failures before a link is flagged BROKEN (one failure = WARNING)
 * @param connectTimeout   TCP/TLS connect timeout per hop
 * @param readTimeout      socket read timeout per hop
 * @param totalTimeout     hard deadline for a whole inspection (all hops + body)
 * @param maxRedirects     hops followed before giving up
 * @param maxBodyBytes     HTML read to find the title (1 MiB: React sites put ~300 KB of inline CSS first);
 *                         reading stops at </title>, and the rest is never downloaded
 * @param previewMaxAge    a stored inspection younger than this is reused by the preview page
 * @param manualCooldown   minimum gap between on-demand checks of one link
 * @param allowLoopback    TESTS ONLY: permit 127.0.0.0/8 so tests can use a local HTTP server
 */
@Validated
@ConfigurationProperties(prefix = "app.health")
public record HealthProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration pollInterval,
        @DefaultValue("20") @Min(1) int batchSize,
        @DefaultValue("8") @Min(1) int concurrency,
        @DefaultValue("24h") Duration healthyInterval,
        @DefaultValue("15m") Duration retryBase,
        @DefaultValue("24h") Duration retryMax,
        @DefaultValue("2") @Min(1) int failureThreshold,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("10s") Duration totalTimeout,
        @DefaultValue("5") @Min(0) int maxRedirects,
        @DefaultValue("1048576") @Min(1024) int maxBodyBytes,
        @DefaultValue("6h") Duration previewMaxAge,
        @DefaultValue("60s") Duration manualCooldown,
        @DefaultValue("false") boolean allowLoopback) {
}
