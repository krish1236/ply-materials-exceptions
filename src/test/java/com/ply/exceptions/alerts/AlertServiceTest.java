package com.ply.exceptions.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.PostgresTestBase;
import com.ply.exceptions.rules.Candidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestBase.class)
class AlertServiceTest {

    @Autowired AlertService service;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM alerts");
    }

    @Test
    void processing_one_candidate_creates_one_open_alert() {
        Candidate c = candidate("COUNT_LIKELY_STALE", "medium", "job_1", "BRK-20A", "truck_8",
                "k:1");

        service.processEvaluation(List.of(c));

        List<Alert> alerts = service.findAll();
        assertThat(alerts).hasSize(1);
        Alert a = alerts.get(0);
        assertThat(a.status()).isEqualTo("open");
        assertThat(a.type()).isEqualTo("COUNT_LIKELY_STALE");
        assertThat(a.affectedJobId()).isEqualTo("job_1");
        assertThat(a.evidence().get("qty").asInt()).isEqualTo(2);
    }

    @Test
    void processing_same_candidate_twice_keeps_one_row() {
        Candidate c = candidate("COUNT_LIKELY_STALE", "medium", "job_1", "BRK-20A", "truck_8",
                "k:dup");

        service.processEvaluation(List.of(c));
        service.processEvaluation(List.of(c));

        assertThat(service.findAll()).hasSize(1);
    }

    @Test
    void candidate_dropping_out_between_runs_auto_resolves_previous_alert() {
        Candidate c1 = candidate("COUNT_LIKELY_STALE", "medium", "job_1", "BRK-20A", "truck_8", "k:a");
        Candidate c2 = candidate("COUNT_LIKELY_STALE", "medium", "job_2", "CAP-45", "truck_12", "k:b");

        service.processEvaluation(List.of(c1, c2));
        sleepMs(5);
        service.processEvaluation(List.of(c1));

        Alert aliveStill = service.findByDedupeKey("k:a").orElseThrow();
        Alert gone = service.findByDedupeKey("k:b").orElseThrow();

        assertThat(aliveStill.status()).isEqualTo("open");
        assertThat(gone.status()).isEqualTo("auto_resolved");
        assertThat(gone.resolvedAt()).isNotNull();
    }

    @Test
    void auto_resolved_alert_reopens_when_candidate_returns() {
        Candidate c = candidate("COUNT_LIKELY_STALE", "medium", "job_1", "BRK-20A", "truck_8", "k:flap");

        service.processEvaluation(List.of(c));
        sleepMs(5);
        service.processEvaluation(List.of());
        assertThat(service.findByDedupeKey("k:flap").orElseThrow().status()).isEqualTo("auto_resolved");

        sleepMs(5);
        service.processEvaluation(List.of(c));
        Alert reopened = service.findByDedupeKey("k:flap").orElseThrow();
        assertThat(reopened.status()).isEqualTo("open");
        assertThat(reopened.resolvedAt()).isNull();
    }

    @Test
    void acknowledge_transitions_open_to_acknowledged() {
        Candidate c = candidate("COUNT_LIKELY_STALE", "medium", "job_1", "BRK-20A", "truck_8", "k:ack");
        service.processEvaluation(List.of(c));
        Alert a = service.findByDedupeKey("k:ack").orElseThrow();

        boolean ok = service.acknowledge(a.id());

        assertThat(ok).isTrue();
        assertThat(service.findById(a.id()).orElseThrow().status()).isEqualTo("acknowledged");
    }

    @Test
    void resolve_transitions_to_resolved_and_sets_resolved_at() {
        Candidate c = candidate("COUNT_LIKELY_STALE", "medium", "job_1", "BRK-20A", "truck_8", "k:res");
        service.processEvaluation(List.of(c));
        Alert a = service.findByDedupeKey("k:res").orElseThrow();

        boolean ok = service.resolve(a.id());

        assertThat(ok).isTrue();
        Alert after = service.findById(a.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("resolved");
        assertThat(after.resolvedAt()).isNotNull();
    }

    @Test
    void acknowledged_alert_whose_candidate_drops_out_auto_resolves() {
        Candidate c = candidate("COUNT_LIKELY_STALE", "medium", "job_1", "BRK-20A", "truck_8", "k:ack2");
        service.processEvaluation(List.of(c));
        Alert a = service.findByDedupeKey("k:ack2").orElseThrow();
        service.acknowledge(a.id());

        sleepMs(5);
        service.processEvaluation(List.of());

        assertThat(service.findById(a.id()).orElseThrow().status()).isEqualTo("auto_resolved");
    }

    @Test
    void human_resolved_alert_is_not_reopened_by_returning_candidate() {
        Candidate c = candidate("COUNT_LIKELY_STALE", "medium", "job_1", "BRK-20A", "truck_8", "k:sticky");
        service.processEvaluation(List.of(c));
        Alert a = service.findByDedupeKey("k:sticky").orElseThrow();
        service.resolve(a.id());

        sleepMs(5);
        service.processEvaluation(List.of(c));

        Alert after = service.findByDedupeKey("k:sticky").orElseThrow();
        assertThat(after.status()).isEqualTo("resolved");
    }

    @Test
    void find_open_orders_by_severity_then_created_at() {
        service.processEvaluation(List.of(
                candidate("A", "low", "job_1", "BRK", "truck_8", "k:low"),
                candidate("B", "high", "job_2", "CAP", "truck_12", "k:high"),
                candidate("C", "medium", "job_3", "THERM", "truck_8", "k:med")
        ));

        List<Alert> open = service.findOpen();

        assertThat(open).extracting(Alert::severity)
                .containsExactly("high", "medium", "low");
    }

    private Candidate candidate(String type, String severity, String jobId, String sku,
                                String locationId, String dedupeKey) {
        var evidence = mapper.createObjectNode().put("qty", 2).put("note", "test");
        return new Candidate(
                type, severity, "Test cause", "Test action",
                "Summary for " + dedupeKey,
                jobId, sku, locationId, evidence, dedupeKey
        );
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
