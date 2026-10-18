package com.linklens.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs: the click flusher and rate-limit bucket eviction. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
