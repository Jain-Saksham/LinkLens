package com.linklens.repository;

import com.linklens.domain.ClickEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Aggregations run in PostgreSQL (GROUP BY over the covering index) so only a few dozen summary rows
 * cross the wire, however many clicks a link has.
 */
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    interface DailyCount {
        /** UTC calendar day, ISO format (yyyy-MM-dd). */
        String getDay();

        long getClicks();
    }

    interface ReferrerCount {
        /** Referring host, or null for direct/unknown traffic. */
        String getReferrerHost();

        long getClicks();
    }

    @Query(value = """
            SELECT to_char(clicked_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, COUNT(*) AS clicks
            FROM click_events
            WHERE short_url_id = :shortUrlId AND clicked_at >= :from
            GROUP BY day
            ORDER BY day
            """, nativeQuery = true)
    List<DailyCount> countDaily(@Param("shortUrlId") Long shortUrlId, @Param("from") Instant from);

    @Query(value = """
            SELECT referrer_host AS referrerHost, COUNT(*) AS clicks
            FROM click_events
            WHERE short_url_id = :shortUrlId AND clicked_at >= :from
            GROUP BY referrer_host
            ORDER BY clicks DESC, referrer_host
            LIMIT :limit
            """, nativeQuery = true)
    List<ReferrerCount> topReferrers(@Param("shortUrlId") Long shortUrlId, @Param("from") Instant from,
                                     @Param("limit") int limit);
}
