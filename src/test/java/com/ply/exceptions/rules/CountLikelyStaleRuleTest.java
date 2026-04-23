package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.domain.Job;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CountLikelyStaleRuleTest {

    private static final Instant NOW = Instant.parse("2026-04-22T10:00:00Z");
    private final CountLikelyStaleRule rule = new CountLikelyStaleRule(new ObjectMapper());

    @Test
    void fires_when_truck_stock_confidence_is_below_threshold() {
        JobRiskView view = viewWithTruckStock(
                sku("BRK-20A", 2,
                        loc("truck_8", 2, 0.35, "PART_USED", 4),
                        loc("wh_A", 10, 0.90, "STOCK_SCAN", 0)));

        List<Candidate> candidates = rule.evaluate(view);

        assertThat(candidates).hasSize(1);
        Candidate c = candidates.get(0);
        assertThat(c.type()).isEqualTo(CountLikelyStaleRule.TYPE);
        assertThat(c.jobId()).isEqualTo("job_4721");
        assertThat(c.sku()).isEqualTo("BRK-20A");
        assertThat(c.locationId()).isEqualTo("truck_8");
        assertThat(c.probableCause()).isEqualTo("Count likely stale");
        assertThat(c.suggestedAction()).contains("wh_A");
        assertThat(c.dedupeKey()).isEqualTo("COUNT_LIKELY_STALE:job_4721:BRK-20A:truck_8");
    }

    @Test
    void does_not_fire_when_truck_stock_confidence_is_high() {
        JobRiskView view = viewWithTruckStock(
                sku("BRK-20A", 2,
                        loc("truck_8", 2, 0.85, "STOCK_SCAN", 0)));

        List<Candidate> candidates = rule.evaluate(view);

        assertThat(candidates).isEmpty();
    }

    @Test
    void falls_back_to_cycle_count_when_no_high_confidence_alternative_exists() {
        JobRiskView view = viewWithTruckStock(
                sku("BRK-20A", 2,
                        loc("truck_8", 2, 0.30, "PART_USED", 5),
                        loc("wh_A", 10, 0.40, "STOCK_ADJUSTMENT", 3)));

        List<Candidate> candidates = rule.evaluate(view);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).suggestedAction()).contains("Cycle-count");
    }

    @Test
    void emits_one_candidate_per_required_item_with_stale_count() {
        JobRiskView view = viewWithTruckStock(
                sku("BRK-20A", 2,
                        loc("truck_8", 2, 0.35, "PART_USED", 4),
                        loc("wh_A", 5, 0.85, "STOCK_SCAN", 0)),
                sku("CAP-45", 1,
                        loc("truck_8", 1, 0.30, "STOCK_TRANSFER", 3),
                        loc("wh_A", 3, 0.80, "STOCK_SCAN", 0)));

        List<Candidate> candidates = rule.evaluate(view);

        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(Candidate::sku).containsExactlyInAnyOrder("BRK-20A", "CAP-45");
    }

    @Test
    void skips_items_not_present_on_assigned_truck() {
        JobRiskView view = viewWithTruckStock(
                sku("BRK-20A", 2,
                        loc("wh_A", 5, 0.85, "STOCK_SCAN", 0)));

        List<Candidate> candidates = rule.evaluate(view);

        assertThat(candidates).isEmpty();
    }

    @Test
    void skips_when_job_has_no_assigned_truck() {
        Job job = new Job("job_no_truck", NOW.plus(java.time.Duration.ofHours(12)),
                "Customer", "alex", null, "scheduled", List.of(new Job.JobRequirement("BRK-20A", 2)));
        JobRiskView view = new JobRiskView(job, List.of(
                new JobRiskView.PerItem("BRK-20A", 2,
                        List.of(loc("truck_8", 2, 0.30, "PART_USED", 4)),
                        List.of(), null)
        ), NOW);

        List<Candidate> candidates = rule.evaluate(view);

        assertThat(candidates).isEmpty();
    }

    private JobRiskView viewWithTruckStock(JobRiskView.PerItem... items) {
        Job job = new Job(
                "job_4721",
                NOW.plus(java.time.Duration.ofHours(12)),
                "Acme Offices",
                "alex",
                "truck_8",
                "scheduled",
                java.util.Arrays.stream(items)
                        .map(i -> new Job.JobRequirement(i.sku(), i.required()))
                        .toList()
        );
        return new JobRiskView(job, List.of(items), NOW);
    }

    private JobRiskView.PerItem sku(String sku, int required, JobRiskView.LocationState... states) {
        return new JobRiskView.PerItem(sku, required, List.of(states), List.of(), null);
    }

    private JobRiskView.LocationState loc(String id, int qty, double confidence, String lastEventType, int eventsSinceScan) {
        return new JobRiskView.LocationState(id, qty, confidence, NOW.minus(java.time.Duration.ofDays(3)),
                eventsSinceScan, lastEventType);
    }
}
