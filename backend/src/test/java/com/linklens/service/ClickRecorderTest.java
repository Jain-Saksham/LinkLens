package com.linklens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

class ClickRecorderTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String INSERT = "INSERT INTO click_events (short_url_id, clicked_at, referrer_host) VALUES (?, ?, ?)";
    private static final String INCREMENT = "UPDATE short_urls SET click_count = click_count + ? WHERE id = ?";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @SuppressWarnings("unchecked")
    void writesOneInsertBatchAndOneAggregatedIncrementPerLink() {
        ClickRecorder recorder = new ClickRecorder(jdbc, TransactionOperations.withoutTransaction(), meters, 100);
        recorder.record(2L, "a.com", AT);
        recorder.record(1L, null, AT);
        recorder.record(2L, null, AT);

        assertThat(recorder.flush()).isEqualTo(3);

        ArgumentCaptor<List<Object[]>> inserts = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Object[]>> increments = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(eq(INSERT), inserts.capture());
        verify(jdbc).batchUpdate(eq(INCREMENT), increments.capture());
        assertThat(inserts.getValue()).hasSize(3);
        // Aggregated (+2 for link 2, +1 for link 1) and ordered by id for a consistent lock order.
        assertThat(increments.getValue()).containsExactly(new Object[] {1, 1L}, new Object[] {2, 2L});
        assertThat(recorder.queued()).isZero();
    }

    @Test
    void largeBacklogsAreWrittenInBoundedBatches() {
        ClickRecorder recorder = new ClickRecorder(jdbc, TransactionOperations.withoutTransaction(), meters, 5_000);
        for (int i = 0; i < 2_500; i++) {
            recorder.record(1L, null, AT);
        }

        assertThat(recorder.flush()).isEqualTo(2_500);
        verify(jdbc, times(3)).batchUpdate(eq(INSERT), anyList()); // 1000 + 1000 + 500
    }

    @Test
    void dropsAndCountsClicksWhenTheQueueIsFullInsteadOfBlocking() {
        ClickRecorder recorder = new ClickRecorder(jdbc, TransactionOperations.withoutTransaction(), meters, 2);
        recorder.record(1L, null, AT);
        recorder.record(1L, null, AT);
        recorder.record(1L, null, AT);

        assertThat(recorder.queued()).isEqualTo(2);
        assertThat(meters.counter("linklens.clicks.dropped").count()).isEqualTo(1);
    }

    @Test
    void aFailedBatchIsCountedAndDiscardedSoTheNextOneStillWrites() {
        when(jdbc.batchUpdate(eq(INSERT), anyList()))
                .thenThrow(new CannotGetJdbcConnectionException("db down"))
                .thenReturn(new int[0]);
        ClickRecorder recorder = new ClickRecorder(jdbc, TransactionOperations.withoutTransaction(), meters, 100);

        recorder.record(1L, null, AT);
        assertThat(recorder.flush()).isZero();
        assertThat(meters.counter("linklens.clicks.failed").count()).isEqualTo(1);

        recorder.record(1L, null, AT);
        assertThat(recorder.flush()).isEqualTo(1);
    }

    @Test
    void emptyQueueDoesNoDatabaseWork() {
        new ClickRecorder(jdbc, TransactionOperations.withoutTransaction(), meters, 10).flush();
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }
}
