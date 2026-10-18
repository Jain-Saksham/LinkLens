package com.linklens.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linklens.dto.AnalyticsResponse;
import com.linklens.dto.ShortUrlResponse;
import com.linklens.exception.AliasUnavailableException;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.ratelimit.RateLimiter;
import com.linklens.service.AnalyticsService;
import com.linklens.service.ShortUrlService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;

@WebMvcTest(ShortUrlController.class)
@Import(RateLimiter.class)
class ShortUrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShortUrlService service;
    @MockitoBean
    private AnalyticsService analyticsService;

    private static final ShortUrlResponse RESPONSE = new ShortUrlResponse(
            "abc1234", "http://localhost:8080/abc1234", "https://example.com",
            false, 0, Instant.parse("2026-01-01T00:00:00Z"), null);

    @Test
    void createReturns201WithLocationBodyAndRateLimitHeaders() throws Exception {
        when(service.create(any())).thenReturn(RESPONSE);

        mockMvc.perform(createRequest("{\"url\":\"https://example.com\"}", "10.0.0.1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/urls/abc1234"))
                .andExpect(header().string("X-RateLimit-Limit", "20"))
                .andExpect(header().string("X-RateLimit-Remaining", "19"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/abc1234"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void createReturns400WithFieldErrorsForInvalidInput() throws Exception {
        mockMvc.perform(createRequest("""
                        {"url":"ftp://example.com","customAlias":"no spaces!","expiresAt":"2000-01-01T00:00:00Z"}
                        """, "10.0.0.2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.url").exists())
                .andExpect(jsonPath("$.errors.customAlias").exists())
                .andExpect(jsonPath("$.errors.expiresAt").exists());
        verifyNoInteractions(service);
    }

    @Test
    void createReturns400ForMalformedJsonAndOversizedUrl() throws Exception {
        mockMvc.perform(createRequest("{\"url\":", "10.0.0.3")).andExpect(status().isBadRequest());
        mockMvc.perform(createRequest("{\"url\":\"https://example.com/" + "a".repeat(2048) + "\"}", "10.0.0.3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.url").exists());
    }

    @Test
    void createReturns409WhenAliasUnavailable() throws Exception {
        when(service.create(any())).thenThrow(new AliasUnavailableException("taken"));

        mockMvc.perform(createRequest("{\"url\":\"https://example.com\",\"customAlias\":\"taken\"}", "10.0.0.4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Alias unavailable"));
    }

    @Test
    void createReturns429WithRetryAfterOnceTheBucketIsEmpty() throws Exception {
        when(service.create(any())).thenReturn(RESPONSE);
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(createRequest("{\"url\":\"https://example.com\"}", "10.9.9.9")).andExpect(status().isCreated());
        }

        mockMvc.perform(createRequest("{\"url\":\"https://example.com\"}", "10.9.9.9"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.title").value("Rate limit exceeded"));

        // Other clients are unaffected, and reads are never limited.
        mockMvc.perform(createRequest("{\"url\":\"https://example.com\"}", "10.9.9.10")).andExpect(status().isCreated());
        when(service.getDetails("abc1234")).thenReturn(RESPONSE);
        mockMvc.perform(get("/api/v1/urls/abc1234").with(r -> {
            r.setRemoteAddr("10.9.9.9");
            return r;
        })).andExpect(status().isOk());
    }

    @Test
    void databaseOutageReturns503WithoutLeakingDetails() throws Exception {
        when(service.create(any())).thenThrow(new CannotCreateTransactionException("Connection to db.internal:5432 refused"));

        mockMvc.perform(createRequest("{\"url\":\"https://example.com\"}", "10.0.0.5"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value("Please try again shortly."));
    }

    @Test
    void unexpectedErrorReturnsGeneric500() throws Exception {
        when(service.getDetails("boom")).thenThrow(new IllegalStateException("secret internals"));

        mockMvc.perform(get("/api/v1/urls/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Something went wrong."));
    }

    @Test
    void getDetailsReturns404ForUnknownCode() throws Exception {
        when(service.getDetails("nope")).thenThrow(new ShortUrlNotFoundException("nope"));

        mockMvc.perform(get("/api/v1/urls/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void analyticsDefaultsTo30DaysAndValidatesTheRange() throws Exception {
        when(analyticsService.analyticsFor("abc1234", 30)).thenReturn(new AnalyticsResponse(
                "abc1234", "http://localhost:8080/abc1234", "https://example.com", Instant.EPOCH, null, 3, 30, 3,
                List.of(new AnalyticsResponse.DailyClicks(LocalDate.of(2026, 1, 1), 3)), List.of(), List.of(), Instant.EPOCH));

        mockMvc.perform(get("/api/v1/urls/abc1234/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.totalClicks").value(3));
        mockMvc.perform(get("/api/v1/urls/abc1234/analytics?days=0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/urls/abc1234/analytics?days=91")).andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder createRequest(String json, String clientIp) {
        return post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                });
    }
}
