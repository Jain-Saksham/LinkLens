package com.linklens.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklens.health.StoredHealth.HealthState;
import com.linklens.inspection.InspectionResult.Hop;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC: the health job's queries (SKIP LOCKED, RETURNING, jsonb) are PostgreSQL-specific anyway. */
@Repository
public class LinkHealthRepository {

    /** A link the scheduler has claimed, with what's needed to check and evaluate it. */
    public record DueLink(long shortUrlId, String originalUrl, Instant expiresAt, StoredHealth previous) {
    }

    private static final TypeReference<List<Hop>> CHAIN = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LinkHealthRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Called in the same transaction as the link insert, so every link has a health row. */
    public void createPending(long shortUrlId) {
        jdbc.update("INSERT INTO link_health (short_url_id) VALUES (?) ON CONFLICT DO NOTHING", shortUrlId);
    }

    /**
     * Atomically claims up to {@code limit} due links and pushes their next_check_at out by
     * {@code lease}. {@code FOR UPDATE SKIP LOCKED} lets several app instances poll at once without
     * double-checking a link or blocking each other. The lease means a crash mid-check only delays that
     * link (it becomes due again), and the slow network calls happen outside any transaction.
     */
    public List<DueLink> claimDue(int limit, Duration lease) {
        return jdbc.query("""
                UPDATE link_health h
                SET next_check_at = now() + (? * interval '1 second')
                FROM short_urls s
                WHERE s.id = h.short_url_id
                  AND h.short_url_id IN (
                      SELECT short_url_id FROM link_health
                      WHERE next_check_at <= now()
                      ORDER BY next_check_at
                      LIMIT ?
                      FOR UPDATE SKIP LOCKED)
                RETURNING h.*, s.original_url, s.expires_at
                """,
                (rs, i) -> new DueLink(rs.getLong("short_url_id"), rs.getString("original_url"),
                        instant(rs, "expires_at"), map(rs)),
                lease.toSeconds(), limit);
    }

    public Optional<StoredHealth> find(long shortUrlId) {
        return jdbc.query("SELECT * FROM link_health WHERE short_url_id = ?", (rs, i) -> map(rs), shortUrlId)
                .stream().findFirst();
    }

    public void save(long shortUrlId, StoredHealth h) {
        jdbc.update("""
                UPDATE link_health SET state = ?, issue = ?, http_status = ?, final_url = ?, title = ?,
                    baseline_title = ?, redirect_chain = ?::jsonb, consecutive_failures = ?, checked_at = ?,
                    last_healthy_at = ?, next_check_at = ?
                WHERE short_url_id = ?
                """,
                h.state().name(), h.issue(), h.httpStatus(), truncate(h.finalUrl(), 2048), truncate(h.title(), 300),
                truncate(h.baselineTitle(), 300), json(h.redirectChain()), h.consecutiveFailures(),
                ts(h.checkedAt()), ts(h.lastHealthyAt()), ts(h.nextCheckAt()), shortUrlId);
    }

    /** Expired links are never checked again. */
    public void retire(long shortUrlId) {
        jdbc.update("UPDATE link_health SET next_check_at = 'infinity' WHERE short_url_id = ?", shortUrlId);
    }

    private StoredHealth map(ResultSet rs) throws SQLException {
        return new StoredHealth(
                HealthState.valueOf(rs.getString("state")),
                rs.getString("issue"),
                (Integer) rs.getObject("http_status"),
                rs.getString("final_url"),
                rs.getString("title"),
                rs.getString("baseline_title"),
                chain(rs.getString("redirect_chain")),
                rs.getInt("consecutive_failures"),
                instant(rs, "checked_at"),
                instant(rs, "last_healthy_at"),
                instant(rs, "next_check_at"));
    }

    private List<Hop> chain(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return mapper.readValue(json, CHAIN);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String json(List<Hop> chain) {
        try {
            return mapper.writeValueAsString(chain == null ? List.of() : chain);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        // 'infinity' comes back as a far-future timestamp; treat anything absurd as "never".
        return t == null || t.getTime() > Instant.parse("9000-01-01T00:00:00Z").toEpochMilli() ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
