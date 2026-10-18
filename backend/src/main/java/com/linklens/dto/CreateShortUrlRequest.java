package com.linklens.dto;

import com.linklens.validation.HttpUrl;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateShortUrlRequest(
        @NotBlank
        @Size(max = 2048)
        @HttpUrl
        String url,

        @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$",
                message = "must be 3-32 characters: letters, digits, '-' or '_'")
        String customAlias,

        @Future
        Instant expiresAt) {
}
