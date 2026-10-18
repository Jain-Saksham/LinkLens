package com.linklens.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "click_events")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // A plain FK column, not @ManyToOne: events are write-only from Java and never navigated,
    // so a relationship would only add the risk of accidental lazy loads.
    @Column(name = "short_url_id", nullable = false, updatable = false)
    private Long shortUrlId;

    @Column(name = "clicked_at", nullable = false, updatable = false)
    private Instant clickedAt;

    @Column(name = "referrer_host", length = 255, updatable = false)
    private String referrerHost;

    protected ClickEvent() {
        // required by JPA
    }

    public ClickEvent(Long shortUrlId, Instant clickedAt, String referrerHost) {
        this.shortUrlId = shortUrlId;
        this.clickedAt = clickedAt;
        this.referrerHost = referrerHost;
    }

    public Long getId() {
        return id;
    }

    public Long getShortUrlId() {
        return shortUrlId;
    }

    public Instant getClickedAt() {
        return clickedAt;
    }

    public String getReferrerHost() {
        return referrerHost;
    }
}
