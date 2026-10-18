package com.linklens.config;

import com.linklens.ratelimit.RateLimitInterceptor;
import com.linklens.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({AppProperties.class, CacheProperties.class, RateLimitProperties.class,
        HealthProperties.class, LiveProperties.class})
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;
    private final RateLimitProperties rateLimitProperties;
    private final RateLimiter rateLimiter;

    public WebConfig(AppProperties properties, RateLimitProperties rateLimitProperties, RateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimitProperties = rateLimitProperties;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.corsAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .exposedHeaders("Location", "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (rateLimitProperties.enabled()) {
            // Both write paths: creating links, and on-demand health checks (each costs an outbound request).
            registry.addInterceptor(new RateLimitInterceptor(rateLimiter))
                    .addPathPatterns("/api/v1/urls", "/api/v1/urls/*/health/check");
        }
    }

    /**
     * Injected wherever "now" matters, so tests can pin time instead of sleeping.
     * Ticks in microseconds because PostgreSQL timestamptz stores microseconds: with Linux's nanosecond
     * clock, a freshly created object would otherwise report a different instant than the same object
     * read back from the database ("...806017002Z" vs "...806017Z").
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
