package com.ply.exceptions.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.ply.exceptions.events.EventEnvelope;
import com.ply.exceptions.events.EventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class StockProjectionUpdater implements ProjectionUpdater {

    private final JdbcTemplate jdbc;

    public StockProjectionUpdater(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(EventEnvelope env) {
        switch (env.type()) {
            case STOCK_SCAN -> applyScan(env);
            case STOCK_TRANSFER -> applyTransfer(env);
            case STOCK_ADJUSTMENT -> applyAdjustment(env);
            case PART_USED -> applyPartUsed(env);
            case PO_RECEIVED -> applyReceived(env);
            default -> { /* not a stock event */ }
        }
    }

    private void applyReceived(EventEnvelope env) {
        JsonNode p = env.payload();
        String location = text(p, "received_at_location");
        JsonNode lines = p.get("lines");
        if (lines == null || !lines.isArray()) {
            return;
        }
        for (JsonNode line : lines) {
            String sku = text(line, "sku");
            int qty = intField(line, "qty");
            upsertReceive(sku, location, qty, env.occurredAt(), env.source(), env.type());
        }
    }

    private void upsertReceive(String sku, String location, int qty, Instant at,
                               String source, EventType type) {
        jdbc.update("""
                INSERT INTO stock_projection
                    (sku, location_id, qty, last_event_at, last_scan_at,
                     last_event_source, last_event_type, events_since_scan)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (sku, location_id) DO UPDATE SET
                    qty = stock_projection.qty + EXCLUDED.qty,
                    last_event_at = EXCLUDED.last_event_at,
                    last_scan_at = EXCLUDED.last_scan_at,
                    last_event_source = EXCLUDED.last_event_source,
                    last_event_type = EXCLUDED.last_event_type,
                    events_since_scan = 0
                """,
                sku, location, qty,
                Timestamp.from(at),
                Timestamp.from(at),
                source, type.name());
    }

    private void applyScan(EventEnvelope env) {
        JsonNode p = env.payload();
        String sku = text(p, "sku");
        String location = text(p, "location");
        int qty = intField(p, "qty");
        upsertSet(sku, location, qty, env.occurredAt(), env.source(), env.type(), true);
    }

    private void applyTransfer(EventEnvelope env) {
        JsonNode p = env.payload();
        String sku = text(p, "sku");
        String from = text(p, "from_location");
        String to = text(p, "to_location");
        int qty = intField(p, "qty");
        upsertDelta(sku, from, -qty, env.occurredAt(), env.source(), env.type());
        upsertDelta(sku, to, qty, env.occurredAt(), env.source(), env.type());
    }

    private void applyAdjustment(EventEnvelope env) {
        JsonNode p = env.payload();
        String sku = text(p, "sku");
        String location = text(p, "location");
        int delta = intField(p, "delta");
        upsertDelta(sku, location, delta, env.occurredAt(), env.source(), env.type());
    }

    private void applyPartUsed(EventEnvelope env) {
        JsonNode p = env.payload();
        String sku = text(p, "sku");
        String from = text(p, "from_location");
        int qty = intField(p, "qty");
        upsertDelta(sku, from, -qty, env.occurredAt(), env.source(), env.type());
    }

    private void upsertSet(String sku, String location, int qty, Instant at,
                           String source, EventType type, boolean isScan) {
        jdbc.update("""
                INSERT INTO stock_projection
                    (sku, location_id, qty, last_event_at, last_scan_at,
                     last_event_source, last_event_type, events_since_scan)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (sku, location_id) DO UPDATE SET
                    qty = EXCLUDED.qty,
                    last_event_at = EXCLUDED.last_event_at,
                    last_scan_at = EXCLUDED.last_scan_at,
                    last_event_source = EXCLUDED.last_event_source,
                    last_event_type = EXCLUDED.last_event_type,
                    events_since_scan = 0
                """,
                sku, location, qty,
                Timestamp.from(at),
                isScan ? Timestamp.from(at) : null,
                source, type.name());
    }

    private void upsertDelta(String sku, String location, int delta, Instant at,
                             String source, EventType type) {
        jdbc.update("""
                INSERT INTO stock_projection
                    (sku, location_id, qty, last_event_at, last_event_source, last_event_type, events_since_scan)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (sku, location_id) DO UPDATE SET
                    qty = stock_projection.qty + EXCLUDED.qty,
                    last_event_at = EXCLUDED.last_event_at,
                    last_event_source = EXCLUDED.last_event_source,
                    last_event_type = EXCLUDED.last_event_type,
                    events_since_scan = stock_projection.events_since_scan + 1
                """,
                sku, location, delta,
                Timestamp.from(at),
                source, type.name());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return v.asText();
    }

    private static int intField(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return v.asInt();
    }
}
