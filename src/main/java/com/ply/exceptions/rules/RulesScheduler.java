package com.ply.exceptions.rules;

import com.ply.exceptions.alerts.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ply.rules.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class RulesScheduler {

    private static final Logger log = LoggerFactory.getLogger(RulesScheduler.class);

    private final JobRiskViewBuilder viewBuilder;
    private final RulesEngine rules;
    private final AlertService alerts;
    private final Clock clock;

    public RulesScheduler(JobRiskViewBuilder viewBuilder,
                          RulesEngine rules,
                          AlertService alerts,
                          Clock clock) {
        this.viewBuilder = viewBuilder;
        this.rules = rules;
        this.alerts = alerts;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ply.rules.poll-ms:30000}")
    public void tick() {
        try {
            Instant now = clock.instant();
            long days = Long.parseLong(System.getProperty("ply.rules.window-days", "14"));
            List<JobRiskView> views = viewBuilder.buildForScheduledBetween(
                    now, now.plus(Duration.ofDays(days)), now);
            List<Candidate> candidates = rules.evaluateAll(views);
            alerts.processEvaluation(candidates);
            log.debug("rules pass: jobs={} candidates={}", views.size(), candidates.size());
        } catch (Exception e) {
            log.error("rules pass failed", e);
        }
    }
}
