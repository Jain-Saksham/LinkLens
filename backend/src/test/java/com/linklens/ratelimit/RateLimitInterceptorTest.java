package com.linklens.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitInterceptorTest {

    private final RateLimiter limiter = new RateLimiter(new com.linklens.config.RateLimitProperties(
            true, 20, java.time.Duration.ofMinutes(1), ""));

    @Test
    void usesTheTrustedEdgeHeaderAndIgnoresClientSuppliedForwardedFor() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(limiter, "CF-Connecting-IP");
        MockHttpServletRequest request = request("10.0.0.9");
        request.addHeader("CF-Connecting-IP", "198.51.100.23");
        request.addHeader("X-Forwarded-For", "203.0.113.77"); // spoofed by the client

        assertThat(interceptor.clientKey(request)).isEqualTo("198.51.100.23");
    }

    @Test
    void fallsBackToTheRemoteAddressWhenTheHeaderIsAbsentOrNotConfigured() {
        assertThat(new RateLimitInterceptor(limiter, "CF-Connecting-IP").clientKey(request("10.0.0.9"))).isEqualTo("10.0.0.9");

        MockHttpServletRequest withHeader = request("10.0.0.9");
        withHeader.addHeader("CF-Connecting-IP", "198.51.100.23");
        assertThat(new RateLimitInterceptor(limiter, "").clientKey(withHeader)).isEqualTo("10.0.0.9");
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/urls");
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
