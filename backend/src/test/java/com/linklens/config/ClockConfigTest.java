package com.linklens.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.RepeatedTest;

class ClockConfigTest {

    /** Regression: CI on Linux returned nanosecond instants that differed from their stored (µs) copies. */
    @RepeatedTest(20)
    void applicationClockMatchesDatabaseTimestampPrecision() {
        Instant now = Instant.now(new WebConfig(null, null, null).clock());
        assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    }
}
