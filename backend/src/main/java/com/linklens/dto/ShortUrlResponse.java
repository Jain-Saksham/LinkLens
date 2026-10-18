package com.linklens.dto;

import java.time.Instant;

public record ShortUrlResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        boolean customAlias,
        long clickCount,
        Instant createdAt,
        Instant expiresAt) {
}
