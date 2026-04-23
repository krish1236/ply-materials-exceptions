package com.ply.exceptions.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ply.exceptions.PostgresTestBase;
import com.ply.exceptions.domain.PurchaseOrder;
import com.ply.exceptions.domain.PurchaseOrderRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestBase.class)
class PoStateProjectorTest {

    @Autowired EventStore store;
    @Autowired Projector projector;
    @Autowired PurchaseOrderRepository pos;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM stock_projection");
        jdbc.update("DELETE FROM price_baseline");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM purchase_order_lines");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("DELETE FROM job_requirements");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM locations");
        jdbc.update("UPDATE projector_offset SET last_ingested_at = '1970-01-01T00:00:00Z', last_event_id = '00000000-0000-0000-0000-000000000000'");
        jdbc.update("INSERT INTO items (sku, name) VALUES ('BRK-20A', '20A')");
        jdbc.update("INSERT INTO locations (id, kind, label) VALUES ('wh_A', 'warehouse', 'WH A')");
    }

    @Test
    void placed_then_eta_updated_then_received_yields_correct_final_state() {
        JsonNode placed = mapper.createObjectNode()
                .put("po_id", "po_8841")
                .put("vendor", "Ferguson NYC")
                .put("placed_at", "2026-04-22T10:00:00Z")
                .put("eta", "2026-04-25T10:00:00Z")
                .put("linked_job_id", (String) null)
                .set("lines", mapper.createArrayNode().add(
                        mapper.createObjectNode()
                                .put("sku", "BRK-20A")
                                .put("qty", 50)
                                .put("unit_cost", "14.50")
                ));
        store.append(new EventEnvelope("acc", "po_evt_1", EventType.PO_PLACED,
                Instant.parse("2026-04-22T10:00:00Z"), placed));

        JsonNode etaUpd = mapper.createObjectNode()
                .put("po_id", "po_8841")
                .put("new_eta", "2026-04-26T10:00:00Z");
        store.append(new EventEnvelope("acc", "po_evt_2", EventType.PO_ETA_UPDATED,
                Instant.parse("2026-04-23T09:00:00Z"), etaUpd));

        JsonNode received = mapper.createObjectNode()
                .put("po_id", "po_8841")
                .put("received_at_location", "wh_A")
                .put("received_at", "2026-04-26T09:30:00Z")
                .set("lines", mapper.createArrayNode().add(
                        mapper.createObjectNode().put("sku", "BRK-20A").put("qty", 50)
                ));
        store.append(new EventEnvelope("acc", "po_evt_3", EventType.PO_RECEIVED,
                Instant.parse("2026-04-26T09:30:00Z"), received));

        projector.pumpOnce();

        Optional<PurchaseOrder> maybe = pos.findById("po_8841");
        assertThat(maybe).isPresent();
        PurchaseOrder po = maybe.get();
        assertThat(po.vendor()).isEqualTo("Ferguson NYC");
        assertThat(po.eta()).isEqualTo(Instant.parse("2026-04-26T10:00:00Z"));
        assertThat(po.status()).isEqualTo("received");
        assertThat(po.lines()).hasSize(1);
        assertThat(po.lines().get(0).sku()).isEqualTo("BRK-20A");
        assertThat(po.lines().get(0).qty()).isEqualTo(50);

        Integer qty = jdbc.queryForObject(
                "SELECT qty FROM stock_projection WHERE sku = 'BRK-20A' AND location_id = 'wh_A'",
                Integer.class);
        assertThat(qty).isEqualTo(50);
    }
}
