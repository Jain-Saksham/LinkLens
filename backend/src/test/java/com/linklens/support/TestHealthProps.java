package com.linklens.support;

import com.linklens.config.HealthProperties;
import java.time.Duration;

/** HealthProperties with production defaults except short timeouts, so failure tests run fast. */
public final class TestHealthProps {

    private TestHealthProps() {
    }

    public static HealthProperties withLoopback(boolean allowLoopback) {
        return new HealthProperties(true, Duration.ofSeconds(30), 20, 8, Duration.ofHours(24), Duration.ofMinutes(15),
                Duration.ofHours(24), 2, Duration.ofSeconds(1), Duration.ofMillis(800), Duration.ofSeconds(3), 5,
                1_048_576, Duration.ofHours(6), Duration.ofSeconds(60), allowLoopback);
    }
}
