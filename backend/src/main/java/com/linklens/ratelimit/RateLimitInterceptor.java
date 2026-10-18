package com.linklens.ratelimit;

import com.linklens.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applied only to link creation (see WebConfig). Redirects are deliberately not limited: they are
 * cheap, cached and must keep working for legitimate traffic spikes.
 *
 * <p>The key is the client IP: from a configured edge header when present (production on Render sits
 * behind Cloudflare, which sets {@code CF-Connecting-IP} and overwrites client-supplied values),
 * otherwise {@link HttpServletRequest#getRemoteAddr()}. X-Forwarded-For is never trusted for this:
 * verified in production, a client-supplied X-Forwarded-For is passed through, so a client could pick
 * a fresh key on every request.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final String clientIpHeader;

    public RateLimitInterceptor(RateLimiter rateLimiter, String clientIpHeader) {
        this.rateLimiter = rateLimiter;
        this.clientIpHeader = clientIpHeader == null || clientIpHeader.isBlank() ? null : clientIpHeader;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        RateLimiter.Decision decision = rateLimiter.tryAcquire(clientKey(request));
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            // Handled by GlobalExceptionHandler -> 429 ProblemDetail with Retry-After.
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
        return true;
    }

    String clientKey(HttpServletRequest request) {
        if (clientIpHeader != null) {
            String fromEdge = request.getHeader(clientIpHeader);
            if (fromEdge != null && !fromEdge.isBlank()) {
                return fromEdge.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
