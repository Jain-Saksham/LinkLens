package com.linklens.ratelimit;

import com.linklens.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applied only to link creation (see WebConfig). Redirects are deliberately not limited: they are
 * cheap, cached and must keep working for legitimate traffic spikes.
 *
 * <p>The key is {@link HttpServletRequest#getRemoteAddr()}. Behind a trusted proxy, enable
 * {@code server.forward-headers-strategy} so this becomes the real client IP. Reading
 * X-Forwarded-For ourselves would let any client spoof its own key.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        RateLimiter.Decision decision = rateLimiter.tryAcquire(request.getRemoteAddr());
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            // Handled by GlobalExceptionHandler -> 429 ProblemDetail with Retry-After.
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
        return true;
    }
}
