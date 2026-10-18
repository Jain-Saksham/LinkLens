package com.linklens.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Records clicks off the redirect path with write-behind batching.
 *
 * <p>A redirect only appends to a bounded in-memory queue (no I/O, no locks held across I/O). A single
 * scheduled flusher drains it every {@value #FLUSH_INTERVAL_MS} ms and writes each batch in one
 * transaction: a multi-row INSERT of the events and one {@code click_count = click_count + n} UPDATE
 * per link. Measured on a laptop, this took persisted throughput from ~2.9k to tens of thousands of
 * clicks per second (see docs/performance.md).
 *
 * <p>Trade-offs, chosen deliberately:
 * <ul>
 *   <li>Counts appear up to ~{@value #FLUSH_INTERVAL_MS} ms late (fine for analytics).</li>
 *   <li>If the queue is full (database down or far behind), new clicks are dropped and counted in
 *       {@code linklens.clicks.dropped}: redirects never block on analytics.</li>
 *   <li>The queue is flushed on graceful shutdown; a hard crash loses at most what's buffered.</li>
 *   <li>A failed batch is logged and discarded rather than retried, so a poison batch can't wedge the
 *       pipeline.</li>
 * </ul>
 * The counter and the events are written in the same transaction, so they never disagree.
 */
@Service
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);
    static final int QUEUE_CAPACITY = 50_000;
    static final int BATCH_SIZE = 1_000;
    static final long FLUSH_INTERVAL_MS = 200;

    record Click(long shortUrlId, String referrerHost, Instant clickedAt) {
    }

    private final BlockingQueue<Click> queue;
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final Counter dropped;
    private final Counter failed;

    @Autowired
    public ClickRecorder(JdbcTemplate jdbc, TransactionOperations tx, MeterRegistry meters) {
        this(jdbc, tx, meters, QUEUE_CAPACITY);
    }

    ClickRecorder(JdbcTemplate jdbc, TransactionOperations tx, MeterRegistry meters, int capacity) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.dropped = meters.counter("linklens.clicks.dropped");
        this.failed = meters.counter("linklens.clicks.failed");
        meters.gaugeCollectionSize("linklens.clicks.queued", List.of(), queue);
    }

    /** Called on the request thread: O(1), never blocks. */
    public void record(Long shortUrlId, String referrerHost, Instant clickedAt) {
        if (!queue.offer(new Click(shortUrlId, referrerHost, clickedAt))) {
            dropped.increment();
        }
    }

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flushScheduled() {
        flush();
    }

    @PreDestroy
    public void flushOnShutdown() {
        int remaining = flush();
        log.info("Flushed {} buffered clicks on shutdown", remaining);
    }

    /** Drains the queue in batches. Synchronized: the scheduler and shutdown hook may overlap. */
    synchronized int flush() {
        int written = 0;
        List<Click> batch = new ArrayList<>(BATCH_SIZE);
        while (queue.drainTo(batch, BATCH_SIZE) > 0) {
            try {
                write(batch);
                written += batch.size();
            } catch (RuntimeException e) {
                failed.increment(batch.size());
                log.warn("Discarding batch of {} clicks: {}", batch.size(), e.toString());
            }
            batch.clear();
        }
        return written;
    }

    private void write(List<Click> batch) {
        Map<Long, Integer> perLink = new TreeMap<>(); // sorted ids: consistent lock order across writers
        List<Object[]> rows = new ArrayList<>(batch.size());
        for (Click c : batch) {
            perLink.merge(c.shortUrlId(), 1, Integer::sum);
            rows.add(new Object[] {c.shortUrlId(), Timestamp.from(c.clickedAt()), c.referrerHost()});
        }
        List<Object[]> increments = perLink.entrySet().stream()
                .map(e -> new Object[] {e.getValue(), e.getKey()})
                .toList();

        tx.executeWithoutResult(status -> {
            jdbc.batchUpdate("INSERT INTO click_events (short_url_id, clicked_at, referrer_host) VALUES (?, ?, ?)", rows);
            jdbc.batchUpdate("UPDATE short_urls SET click_count = click_count + ? WHERE id = ?", increments);
        });
    }

    int queued() {
        return queue.size();
    }
}
