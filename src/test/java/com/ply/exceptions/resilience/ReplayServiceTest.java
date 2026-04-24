package com.ply.exceptions.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.PostgresTestBase;
import com.ply.exceptions.alerts.AlertService;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.JobRepository;
import com.ply.exceptions.events.EventEnvelope;
import com.ply.exceptions.events.EventStore;
import com.ply.exceptions.events.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestBase.class)
class ReplayServiceTest {

    @Autowired ReplayService replay;
    @Autowired EventStore store;
    @Autowired JobRepository jobs;
    @Autowired AlertService alerts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired Clock clock;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM stock_projection");
        jdbc.update("DELETE FROM price_baseline");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM purchase_order_lines");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("DELETE FROM job_requirements");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM locations");
        jdbc.update("DELETE FROM circuit_state");
        jdbc.update("UPDATE projector_offset SET last_ingested_at = '1970-01-01T00:00:00Z', "
                + "last_event_id = '00000000-0000-0000-0000-000000000000'");

        jdbc.update("INSERT INTO items (sku, name) VALUES ('BRK-20A', '20A')");
        jdbc.update("INSERT INTO locations (id, kind, label) VALUES " +
                "('wh_A', 'warehouse', 'WH A'), " +
                "('truck_8', 'truck', 'Truck 8')");

        Instant tomorrow = clock.instant().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        jobs.upsert(new Job("job_4721", tomorrow, "Acme", "alex", "truck_8", "scheduled",
                List.of(new Job.JobRequirement("BRK-20A", 2))));
    }

    @Test
    void replay_rebuilds_projection_from_events_and_regenerates_alerts() {
        Instant t = clock.instant().minus(Duration.ofDays(3));
        store.append(new EventEnvelope("fsm", "evt_1", EventType.STOCK_SCAN, t,
                payload("sku", "BRK-20A", "location", "truck_8", "qty", 3)));
        store.append(new EventEnvelope("fsm", "evt_2", EventType.PART_USED, t.plusSeconds(60),
                payload("sku", "BRK-20A", "qty", 1, "from_location", "truck_8", "tech_id", "a", "job_id", "j")));
        store.append(new EventEnvelope("fsm", "evt_3", EventType.PART_USED, t.plusSeconds(120),
                payload("sku", "BRK-20A", "qty", 1, "from_location", "truck_8", "tech_id", "a", "job_id", "j")));

        ReplayService.ReplayStats stats = replay.replay();

        assertThat(stats.eventsInLog()).isEqualTo(3);
        assertThat(stats.eventsApplied()).isEqualTo(3);
        assertThat(stats.jobsEvaluated()).isEqualTo(1);

        Integer qty = jdbc.queryForObject(
                "SELECT qty FROM stock_projection WHERE sku = 'BRK-20A' AND location_id = 'truck_8'",
                Integer.class);
        assertThat(qty).isEqualTo(1);

        assertThat(alerts.findOpen()).isNotEmpty();
    }

    @Test
    void replay_is_idempotent_producing_same_projection_twice() {
        Instant t = clock.instant().minus(Duration.ofDays(2));
        store.append(new EventEnvelope("fsm", "evt_a", EventType.STOCK_SCAN, t,
                payload("sku", "BRK-20A", "location", "wh_A", "qty", 8)));
        store.append(new EventEnvelope("fsm", "evt_b", EventType.STOCK_TRANSFER, t.plusSeconds(60),
                payload("sku", "BRK-20A", "qty", 3, "from_location", "wh_A", "to_location", "truck_8")));

        replay.replay();
        int whAfterFirst = jdbc.queryForObject(
                "SELECT qty FROM stock_projection WHERE sku = 'BRK-20A' AND location_id = 'wh_A'",
                Integer.class);
        int truckAfterFirst = jdbc.queryForObject(
                "SELECT qty FROM stock_projection WHERE sku = 'BRK-20A' AND location_id = 'truck_8'",
                Integer.class);

        replay.replay();

        int whAfterSecond = jdbc.queryForObject(
                "SELECT qty FROM stock_projection WHERE sku = 'BRK-20A' AND location_id = 'wh_A'",
                Integer.class);
        int truckAfterSecond = jdbc.queryForObject(
                "SELECT qty FROM stock_projection WHERE sku = 'BRK-20A' AND location_id = 'truck_8'",
                Integer.class);

        assertThat(whAfterSecond).isEqualTo(whAfterFirst);
        assertThat(truckAfterSecond).isEqualTo(truckAfterFirst);
    }

    private com.fasterxml.jackson.databind.JsonNode payload(Object... kv) {
        var node = mapper.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v instanceof Integer n) node.put(k, n);
            else node.put(k, String.valueOf(v));
        }
        return node;
    }
}
