package com.linklens.config;

import com.linklens.ratelimit.RateLimitInterceptor;
import com.linklens.ratelimit.RateLimiter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({AppProperties.class, CacheProperties.class, RateLimitProperties.class})
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
            registry.addInterceptor(new RateLimitInterceptor(rateLimiter)).addPathPatterns("/api/v1/urls");
        }
    }

    /** Injected wherever "now" matters, so tests can pin time instead of sleeping. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
