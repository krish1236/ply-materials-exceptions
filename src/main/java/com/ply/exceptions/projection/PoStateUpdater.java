package com.ply.exceptions.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.ply.exceptions.events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

@Component
public class PoStateUpdater implements ProjectionUpdater {

    private final JdbcTemplate jdbc;

    public PoStateUpdater(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(EventEnvelope env) {
        switch (env.type()) {
            case PO_PLACED -> applyPlaced(env);
            case PO_ETA_UPDATED -> applyEtaUpdated(env);
            case PO_RECEIVED -> applyReceived(env);
            default -> { /* not a po event */ }
        }
    }

    private void applyPlaced(EventEnvelope env) {
        JsonNode p = env.payload();
        String poId = text(p, "po_id");
        String vendor = text(p, "vendor");
        Instant placedAt = Instant.parse(text(p, "placed_at"));
        Instant eta = Instant.parse(text(p, "eta"));
        String linkedJobId = optionalText(p, "linked_job_id");

        jdbc.update("""
                INSERT INTO purchase_orders (id, vendor, placed_at, eta, status, linked_job_id)
                VALUES (?, ?, ?, ?, 'placed', ?)
                ON CONFLICT (id) DO UPDATE SET
                    vendor = EXCLUDED.vendor,
                    placed_at = EXCLUDED.placed_at,
                    eta = EXCLUDED.eta,
                    linked_job_id = EXCLUDED.linked_job_id
                """,
                poId, vendor,
                Timestamp.from(placedAt),
                Timestamp.from(eta),
                linkedJobId);

        JsonNode lines = p.get("lines");
        if (lines != null && lines.isArray()) {
            jdbc.update("DELETE FROM purchase_order_lines WHERE po_id = ?", poId);
            for (JsonNode line : lines) {
                jdbc.update(
                        "INSERT INTO purchase_order_lines (po_id, sku, qty, unit_cost) VALUES (?, ?, ?, ?)",
                        poId,
                        text(line, "sku"),
                        intField(line, "qty"),
                        new BigDecimal(text(line, "unit_cost"))
                );
            }
        }
    }

    private void applyEtaUpdated(EventEnvelope env) {
        JsonNode p = env.payload();
        String poId = text(p, "po_id");
        Instant newEta = Instant.parse(text(p, "new_eta"));
        jdbc.update(
                "UPDATE purchase_orders SET eta = ? WHERE id = ?",
                Timestamp.from(newEta), poId);
    }

    private void applyReceived(EventEnvelope env) {
        JsonNode p = env.payload();
        String poId = text(p, "po_id");
        jdbc.update(
                "UPDATE purchase_orders SET status = 'received' WHERE id = ?",
                poId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return v.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static int intField(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return v.asInt();
    }
}
