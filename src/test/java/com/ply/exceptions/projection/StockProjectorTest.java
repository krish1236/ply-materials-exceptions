package com.ply.exceptions.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.PostgresTestBase;
import com.ply.exceptions.events.EventEnvelope;
import com.ply.exceptions.events.EventStore;
import com.ply.exceptions.events.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestBase.class)
class StockProjectorTest {

    @Autowired EventStore store;
    @Autowired Projector projector;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM stock_projection");
        jdbc.update("DELETE FROM price_baseline");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM job_requirements");
        jdbc.update("DELETE FROM purchase_order_lines");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM locations");
        jdbc.update("UPDATE projector_offset SET last_ingested_at = '1970-01-01T00:00:00Z', last_event_id = '00000000-0000-0000-0000-000000000000' WHERE name = 'stock'");

        jdbc.update("INSERT INTO items (sku, name) VALUES ('BRK-20A', '20A breaker'), ('CAP-45', '45uF cap')");
        jdbc.update("INSERT INTO locations (id, kind, label) VALUES " +
                "('wh_A', 'warehouse', 'WH A'), " +
                "('truck_8', 'truck', 'Truck 8'), " +
                "('truck_12', 'truck', 'Truck 12')");
    }

    @Test
    void scans_transfers_uses_and_adjustments_reconstruct_correctly() {
        Instant t0 = Instant.parse("2026-04-22T08:00:00Z");
        int n = 0;

        append("evt_" + (++n), EventType.STOCK_SCAN, t0, payload("sku", "BRK-20A", "location", "wh_A", "qty", 10));
        append("evt_" + (++n), EventType.STOCK_SCAN, t0.plusSeconds(60), payload("sku", "CAP-45", "location", "wh_A", "qty", 5));
        append("evt_" + (++n), EventType.STOCK_TRANSFER, t0.plusSeconds(120),
                payload("sku", "BRK-20A", "qty", 3, "from_location", "wh_A", "to_location", "truck_8"));
        append("evt_" + (++n), EventType.STOCK_TRANSFER, t0.plusSeconds(180),
                payload("sku", "CAP-45", "qty", 2, "from_location", "wh_A", "to_location", "truck_8"));
        append("evt_" + (++n), EventType.PART_USED, t0.plusSeconds(240),
                payload("sku", "BRK-20A", "qty", 1, "from_location", "truck_8", "tech_id", "alex", "job_id", "job_x"));
        append("evt_" + (++n), EventType.STOCK_ADJUSTMENT, t0.plusSeconds(300),
                payload("sku", "BRK-20A", "location", "wh_A", "delta", -1));
        append("evt_" + (++n), EventType.STOCK_SCAN, t0.plusSeconds(360), payload("sku", "BRK-20A", "location", "truck_8", "qty", 2));
        append("evt_" + (++n), EventType.PART_USED, t0.plusSeconds(420),
                payload("sku", "CAP-45", "qty", 1, "from_location", "truck_8", "tech_id", "alex", "job_id", "job_y"));
        append("evt_" + (++n), EventType.STOCK_TRANSFER, t0.plusSeconds(480),
                payload("sku", "BRK-20A", "qty", 1, "from_location", "truck_8", "to_location", "truck_12"));
        append("evt_" + (++n), EventType.STOCK_SCAN, t0.plusSeconds(540), payload("sku", "CAP-45", "location", "wh_A", "qty", 3));

        projector.pumpOnce();

        assertThat(qtyAt("BRK-20A", "wh_A")).isEqualTo(6);
        assertThat(qtyAt("BRK-20A", "truck_8")).isEqualTo(2);
        assertThat(qtyAt("BRK-20A", "truck_12")).isEqualTo(1);
        assertThat(qtyAt("CAP-45", "wh_A")).isEqualTo(3);
        assertThat(qtyAt("CAP-45", "truck_8")).isEqualTo(1);
    }

    @Test
    void pump_is_idempotent_when_called_twice_with_no_new_events() {
        append("evt_idem_1", EventType.STOCK_SCAN, Instant.parse("2026-04-22T08:00:00Z"),
                payload("sku", "BRK-20A", "location", "wh_A", "qty", 7));

        projector.pumpOnce();
        int qtyAfterFirst = qtyAt("BRK-20A", "wh_A");
        projector.pumpOnce();
        int qtyAfterSecond = qtyAt("BRK-20A", "wh_A");

        assertThat(qtyAfterFirst).isEqualTo(7);
        assertThat(qtyAfterSecond).isEqualTo(7);
    }

    @Test
    void scan_resets_events_since_scan_counter() {
        Instant t = Instant.parse("2026-04-22T08:00:00Z");
        append("evt_c_1", EventType.STOCK_SCAN, t, payload("sku", "BRK-20A", "location", "truck_8", "qty", 5));
        append("evt_c_2", EventType.PART_USED, t.plusSeconds(60),
                payload("sku", "BRK-20A", "qty", 1, "from_location", "truck_8", "tech_id", "a", "job_id", "j"));
        append("evt_c_3", EventType.PART_USED, t.plusSeconds(120),
                payload("sku", "BRK-20A", "qty", 1, "from_location", "truck_8", "tech_id", "a", "job_id", "j"));

        projector.pumpOnce();
        assertThat(eventsSinceScan("BRK-20A", "truck_8")).isEqualTo(2);

        append("evt_c_4", EventType.STOCK_SCAN, t.plusSeconds(180), payload("sku", "BRK-20A", "location", "truck_8", "qty", 3));
        projector.pumpOnce();
        assertThat(eventsSinceScan("BRK-20A", "truck_8")).isEqualTo(0);
        assertThat(qtyAt("BRK-20A", "truck_8")).isEqualTo(3);
    }

    private void append(String externalId, EventType type, Instant at, com.fasterxml.jackson.databind.JsonNode payload) {
        store.append(new EventEnvelope("test", externalId, type, at, payload));
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

    private int qtyAt(String sku, String location) {
        Integer q = jdbc.queryForObject(
                "SELECT qty FROM stock_projection WHERE sku = ? AND location_id = ?",
                Integer.class, sku, location);
        return q == null ? 0 : q;
    }

    private int eventsSinceScan(String sku, String location) {
        Integer n = jdbc.queryForObject(
                "SELECT events_since_scan FROM stock_projection WHERE sku = ? AND location_id = ?",
                Integer.class, sku, location);
        return n == null ? 0 : n;
    }
}
