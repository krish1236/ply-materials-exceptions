package com.ply.exceptions.confidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConfidenceScorerTest {

    private static final Instant NOW = Instant.parse("2026-04-22T10:00:00Z");

    private final ConfidenceScorer scorer = new ConfidenceScorer(null);

    @Test
    void fresh_scan_no_activity_scores_near_one() {
        ScoreInputs in = new ScoreInputs(NOW.minus(1, ChronoUnit.HOURS), 0, "STOCK_SCAN", NOW, 0.0);

        double s = scorer.score(in);

        assertThat(s).isCloseTo(1.0, within(0.05));
    }

    @Test
    void old_scan_reduces_recency_component_only() {
        ScoreInputs fresh = new ScoreInputs(NOW.minus(1, ChronoUnit.HOURS), 0, "STOCK_SCAN", NOW, 0.0);
        ScoreInputs old = new ScoreInputs(NOW.minus(14, ChronoUnit.DAYS), 0, "STOCK_SCAN", NOW, 0.0);

        double freshScore = scorer.score(fresh);
        double oldScore = scorer.score(old);

        assertThat(oldScore).isLessThan(freshScore);
        assertThat(oldScore).isCloseTo(0.60, within(0.05));
    }

    @Test
    void many_events_since_scan_reduces_activity_factor() {
        ScoreInputs zero = new ScoreInputs(NOW.minus(1, ChronoUnit.HOURS), 0, "STOCK_SCAN", NOW, 0.0);
        ScoreInputs ten = new ScoreInputs(NOW.minus(1, ChronoUnit.HOURS), 10, "STOCK_SCAN", NOW, 0.0);

        double a = scorer.score(zero);
        double b = scorer.score(ten);

        assertThat(b).isLessThan(a);
        assertThat(ConfidenceScorer.activityFactor(10)).isCloseTo(Math.pow(0.5, 5), within(0.001));
    }

    @Test
    void trusted_source_scores_higher_than_inferred() {
        ScoreInputs scan = new ScoreInputs(NOW.minus(1, ChronoUnit.HOURS), 0, "STOCK_SCAN", NOW, 0.0);
        ScoreInputs inferred = new ScoreInputs(NOW.minus(1, ChronoUnit.HOURS), 0, "PART_USED", NOW, 0.0);

        assertThat(scorer.score(scan)).isGreaterThan(scorer.score(inferred));
    }

    @Test
    void conflict_penalty_reduces_score() {
        ScoreInputs no = new ScoreInputs(NOW.minus(1, ChronoUnit.HOURS), 0, "STOCK_SCAN", NOW, 0.0);
        ScoreInputs full = new ScoreInputs(NOW.minus(1, ChronoUnit.HOURS), 0, "STOCK_SCAN", NOW, 1.0);

        assertThat(scorer.score(no)).isGreaterThan(scorer.score(full));
    }

    @Test
    void never_scanned_zeros_recency_component() {
        ScoreInputs never = new ScoreInputs(null, 0, null, NOW, 0.0);

        double s = scorer.score(never);

        assertThat(s).isLessThan(0.5);
        assertThat(ConfidenceScorer.recencyFactor(null, NOW)).isEqualTo(0.0);
    }

    @Test
    void source_trust_map_covers_expected_event_types() {
        assertThat(ConfidenceScorer.sourceTrust("STOCK_SCAN")).isEqualTo(0.9);
        assertThat(ConfidenceScorer.sourceTrust("PO_RECEIVED")).isEqualTo(0.9);
        assertThat(ConfidenceScorer.sourceTrust("STOCK_TRANSFER")).isEqualTo(0.7);
        assertThat(ConfidenceScorer.sourceTrust("STOCK_ADJUSTMENT")).isEqualTo(0.5);
        assertThat(ConfidenceScorer.sourceTrust("PART_USED")).isEqualTo(0.3);
        assertThat(ConfidenceScorer.sourceTrust(null)).isEqualTo(0.3);
        assertThat(ConfidenceScorer.sourceTrust("UNKNOWN")).isEqualTo(0.3);
    }

    @Test
    void score_always_in_zero_to_one_range() {
        ScoreInputs worst = new ScoreInputs(null, 1_000_000, null, NOW, 1.0);
        ScoreInputs best = new ScoreInputs(NOW, 0, "STOCK_SCAN", NOW, 0.0);

        assertThat(scorer.score(worst)).isBetween(0.0, 1.0);
        assertThat(scorer.score(best)).isBetween(0.0, 1.0);
    }
}
