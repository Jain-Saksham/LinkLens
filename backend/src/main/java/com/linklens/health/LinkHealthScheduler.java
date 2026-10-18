package com.linklens.health;

import com.linklens.config.HealthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for due links. Safe to run on every instance: claims use SKIP LOCKED. */
@Component
public class LinkHealthScheduler {

    private static final Logger log = LoggerFactory.getLogger(LinkHealthScheduler.class);

    private final LinkHealthService service;
    private final HealthProperties props;

    public LinkHealthScheduler(LinkHealthService service, HealthProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.health.poll-interval:30s}", initialDelayString = "10s")
    public void poll() {
        if (!props.enabled()) {
            return;
        }
        try {
            int checked = service.checkDueBatch();
            if (checked > 0) {
                log.debug("Checked {} links", checked);
            }
        } catch (RuntimeException e) {
            // Database down etc.: try again next poll, never kill the scheduler thread.
            log.warn("Health poll failed: {}", e.toString());
        }
    }
}
