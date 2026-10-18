package com.linklens.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Caps on open live-click streams: each is a held connection, so they're a resource to protect. */
@Validated
@ConfigurationProperties(prefix = "app.live")
public record LiveProperties(
        @DefaultValue("50") @Min(1) int maxSubscribersPerLink,
        @DefaultValue("1000") @Min(1) int maxSubscribers) {
}
