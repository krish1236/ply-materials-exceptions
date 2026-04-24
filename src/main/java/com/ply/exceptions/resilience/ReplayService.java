package com.ply.exceptions.resilience;

import com.ply.exceptions.alerts.AlertService;
import com.ply.exceptions.domain.JobRepository;
import com.ply.exceptions.projection.Projector;
import com.ply.exceptions.rules.Candidate;
import com.ply.exceptions.rules.JobRiskView;
import com.ply.exceptions.rules.JobRiskViewBuilder;
import com.ply.exceptions.rules.RulesEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final JdbcTemplate jdbc;
    private final Projector projector;
    private final JobRepository jobs;
    private final JobRiskViewBuilder viewBuilder;
    private final RulesEngine rules;
    private final AlertService alerts;
    private final Clock clock;

    public ReplayService(JdbcTemplate jdbc,
                         Projector projector,
                         JobRepository jobs,
                         JobRiskViewBuilder viewBuilder,
                         RulesEngine rules,
                         AlertService alerts,
                         Clock clock) {
        this.jdbc = jdbc;
        this.projector = projector;
        this.jobs = jobs;
        this.viewBuilder = viewBuilder;
        this.rules = rules;
        this.alerts = alerts;
        this.clock = clock;
    }

    @Transactional
    public ReplayStats replay() {
        long startMs = System.currentTimeMillis();
        log.info("replay starting");

        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM price_baseline");
        jdbc.update("DELETE FROM stock_projection");
        jdbc.update("DELETE FROM purchase_order_lines");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("UPDATE projector_offset SET last_ingested_at = '1970-01-01T00:00:00Z', "
                + "last_event_id = '00000000-0000-0000-0000-000000000000'");

        Long eventCount = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);

        int applied = projector.pumpOnce();

        Instant now = clock.instant();
        Instant windowEnd = now.plus(Duration.ofDays(14));
        List<JobRiskView> views = viewBuilder.buildForScheduledBetween(now, windowEnd, now);
        List<Candidate> candidates = rules.evaluateAll(views);
        alerts.processEvaluation(candidates);

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("replay done: events={} applied={} jobs={} candidates={} elapsedMs={}",
                eventCount, applied, views.size(), candidates.size(), elapsed);

        return new ReplayStats(
                eventCount == null ? 0 : eventCount,
                applied,
                views.size(),
                candidates.size(),
                elapsed
        );
    }

    public record ReplayStats(
            long eventsInLog,
            int eventsApplied,
            int jobsEvaluated,
            int candidatesEmitted,
            long elapsedMs
    ) {}
}
