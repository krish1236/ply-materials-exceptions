package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.PurchaseOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewRulesTest {

    private static final Instant NOW = Instant.parse("2026-04-22T10:00:00Z");
    private static final Instant TOMORROW = NOW.plus(Duration.ofDays(1));

    private final ObjectMapper mapper = new ObjectMapper();
    private final UnrecordedUsageRule unrecorded = new UnrecordedUsageRule(mapper);
    private final UntouchedTooLongRule untouched = new UntouchedTooLongRule(mapper);
    private final PoWillMissJobDateRule poMiss = new PoWillMissJobDateRule(mapper);
    private final ReorderPriceSpikeRule priceSpike = new ReorderPriceSpikeRule(mapper);
    private final MissingPartRiskRule missing = new MissingPartRiskRule(mapper);

    @Test
    void unrecorded_usage_fires_when_last_event_is_part_used_with_soft_confidence() {
        JobRiskView view = jobView(perItem("BRK-20A", 2,
                loc("truck_8", 3, 0.45, "PART_USED", 3, threeDaysAgo())));

        List<Candidate> c = unrecorded.evaluate(view);

        assertThat(c).hasSize(1);
        assertThat(c.get(0).probableCause()).isEqualTo("Unrecorded usage likely");
        assertThat(c.get(0).suggestedAction()).contains("alex");
    }

    @Test
    void unrecorded_usage_does_not_fire_when_last_event_is_a_scan() {
        JobRiskView view = jobView(perItem("BRK-20A", 2,
                loc("truck_8", 3, 0.45, "STOCK_SCAN", 0, now())));

        assertThat(unrecorded.evaluate(view)).isEmpty();
    }

    @Test
    void untouched_fires_when_last_scan_is_14_plus_days_old() {
        JobRiskView view = jobView(perItem("BRK-20A", 2,
                loc("truck_8", 3, 0.50, "STOCK_SCAN", 0, NOW.minus(Duration.ofDays(20)))));

        List<Candidate> c = untouched.evaluate(view);

        assertThat(c).hasSize(1);
        assertThat(c.get(0).probableCause()).isEqualTo("Untouched too long");
    }

    @Test
    void untouched_does_not_fire_when_recent_scan() {
        JobRiskView view = jobView(perItem("BRK-20A", 2,
                loc("truck_8", 3, 0.80, "STOCK_SCAN", 0, NOW.minus(Duration.ofDays(2)))));

        assertThat(untouched.evaluate(view)).isEmpty();
    }

    @Test
    void po_will_miss_fires_when_eta_is_after_job_date() {
        PurchaseOrder late = new PurchaseOrder(
                "po_late", "Ferguson NYC", NOW, TOMORROW.plus(Duration.ofDays(2)),
                "placed", "job_4721",
                List.of(new PurchaseOrder.Line("BRK-20A", 10, new BigDecimal("14.50"))));
        JobRiskView view = jobViewWithPos("BRK-20A", 2, late);

        List<Candidate> c = poMiss.evaluate(view);

        assertThat(c).hasSize(1);
        assertThat(c.get(0).type()).isEqualTo(PoWillMissJobDateRule.TYPE);
        assertThat(c.get(0).suggestedAction()).contains("po_late");
    }

    @Test
    void po_will_miss_does_not_fire_when_eta_is_before_job_date() {
        PurchaseOrder onTime = new PurchaseOrder(
                "po_ok", "Ferguson NYC", NOW, NOW.plus(Duration.ofHours(6)),
                "placed", "job_4721",
                List.of(new PurchaseOrder.Line("BRK-20A", 10, new BigDecimal("14.50"))));
        JobRiskView view = jobViewWithPos("BRK-20A", 2, onTime);

        assertThat(poMiss.evaluate(view)).isEmpty();
    }

    @Test
    void price_spike_fires_when_latest_quote_exceeds_two_stddev() {
        JobRiskView.PriceSignal ps = new JobRiskView.PriceSignal(14.50, 0.50, 16.00, 10);
        JobRiskView view = jobViewWithSignal("BRK-20A", 2, ps);

        List<Candidate> c = priceSpike.evaluate(view);

        assertThat(c).hasSize(1);
        assertThat(c.get(0).probableCause()).isEqualTo("Reorder price spike");
    }

    @Test
    void price_spike_does_not_fire_with_too_few_samples() {
        JobRiskView.PriceSignal ps = new JobRiskView.PriceSignal(14.50, 0.50, 20.00, 3);
        JobRiskView view = jobViewWithSignal("BRK-20A", 2, ps);

        assertThat(priceSpike.evaluate(view)).isEmpty();
    }

    @Test
    void missing_part_fires_when_truck_is_short_and_no_alternative_po() {
        JobRiskView view = jobView(perItem("BRK-20A", 2,
                loc("truck_8", 1, 0.90, "STOCK_SCAN", 0, now()),
                loc("wh_A", 5, 0.90, "STOCK_SCAN", 0, now())));

        List<Candidate> c = missing.evaluate(view);

        assertThat(c).hasSize(1);
        assertThat(c.get(0).probableCause()).isEqualTo("Missing part risk");
        assertThat(c.get(0).suggestedAction()).contains("wh_A");
    }

    @Test
    void missing_part_does_not_fire_when_truck_has_enough() {
        JobRiskView view = jobView(perItem("BRK-20A", 2,
                loc("truck_8", 2, 0.90, "STOCK_SCAN", 0, now())));

        assertThat(missing.evaluate(view)).isEmpty();
    }

    @Test
    void missing_part_does_not_fire_when_a_po_covers_shortage_in_time() {
        PurchaseOrder onTime = new PurchaseOrder(
                "po_cov", "Ferguson NYC", NOW, NOW.plus(Duration.ofHours(12)),
                "placed", "job_4721",
                List.of(new PurchaseOrder.Line("BRK-20A", 5, new BigDecimal("14.50"))));
        JobRiskView view = jobViewWithPos("BRK-20A", 2, onTime);

        assertThat(missing.evaluate(view)).isEmpty();
    }

    private JobRiskView jobView(JobRiskView.PerItem item) {
        Job job = new Job("job_4721", TOMORROW, "Acme", "alex", "truck_8", "scheduled",
                List.of(new Job.JobRequirement(item.sku(), item.required())));
        return new JobRiskView(job, List.of(item), NOW);
    }

    private JobRiskView jobViewWithPos(String sku, int required, PurchaseOrder po) {
        Job job = new Job("job_4721", TOMORROW, "Acme", "alex", "truck_8", "scheduled",
                List.of(new Job.JobRequirement(sku, required)));
        JobRiskView.PerItem item = new JobRiskView.PerItem(
                sku, required,
                List.of(loc("truck_8", 0, 0.80, "STOCK_SCAN", 0, now())),
                List.of(po),
                null
        );
        return new JobRiskView(job, List.of(item), NOW);
    }

    private JobRiskView jobViewWithSignal(String sku, int required, JobRiskView.PriceSignal ps) {
        Job job = new Job("job_4721", TOMORROW, "Acme", "alex", "truck_8", "scheduled",
                List.of(new Job.JobRequirement(sku, required)));
        JobRiskView.PerItem item = new JobRiskView.PerItem(
                sku, required,
                List.of(loc("truck_8", 5, 0.85, "STOCK_SCAN", 0, now())),
                List.of(),
                ps
        );
        return new JobRiskView(job, List.of(item), NOW);
    }

    private JobRiskView.PerItem perItem(String sku, int required, JobRiskView.LocationState... states) {
        return new JobRiskView.PerItem(sku, required, List.of(states), List.of(), null);
    }

    private JobRiskView.LocationState loc(String id, int qty, double confidence,
                                          String lastEventType, int eventsSinceScan, Instant lastScanAt) {
        return new JobRiskView.LocationState(id, qty, confidence, lastScanAt, eventsSinceScan, lastEventType);
    }

    private Instant now() { return NOW.minus(Duration.ofHours(1)); }
    private Instant threeDaysAgo() { return NOW.minus(Duration.ofDays(3)); }
}
