package com.linklens.cache;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * The minimum a redirect needs, stored as JSON in Redis. A null {@code id} marks a cached
 * "not found" (negative entry), so repeated lookups of unknown codes don't each hit the database.
 */
public record CachedLink(Long id, String originalUrl, Instant expiresAt) {

    public static CachedLink notFound() {
        return new CachedLink(null, null, null);
    }

    @JsonIgnore
    public boolean isNotFound() {
        return id == null;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
