package com.ply.exceptions.confidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class ConfidenceScorer {

    static final double W_RECENCY = 0.40;
    static final double W_ACTIVITY = 0.25;
    static final double W_SOURCE = 0.25;
    static final double W_CONFLICT = 0.10;
    static final int RECENCY_HALF_LIFE_DAYS = 14;

    private final JdbcTemplate jdbc;

    public ConfidenceScorer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public double score(ScoreInputs in) {
        double recency = recencyFactor(in.lastScanAt(), in.now());
        double activity = activityFactor(in.eventsSinceScan());
        double source = sourceTrust(in.lastEventType());
        double conflict = 1.0 - clamp(in.conflictPenalty(), 0.0, 1.0);

        double s = W_RECENCY * recency
                + W_ACTIVITY * activity
                + W_SOURCE * source
                + W_CONFLICT * conflict;
        return clamp(s, 0.0, 1.0);
    }

    public double scoreFor(String sku, String locationId, Instant now) {
        List<ScoreInputs> rows = jdbc.query("""
                        SELECT last_scan_at, events_since_scan, last_event_type
                        FROM stock_projection
                        WHERE sku = ? AND location_id = ?
                        """,
                (rs, i) -> new ScoreInputs(
                        rs.getTimestamp("last_scan_at") == null ? null : rs.getTimestamp("last_scan_at").toInstant(),
                        rs.getInt("events_since_scan"),
                        rs.getString("last_event_type"),
                        now,
                        0.0
                ),
                sku, locationId);
        if (rows.isEmpty()) {
            return score(new ScoreInputs(null, Integer.MAX_VALUE, null, now, 0.0));
        }
        return score(rows.get(0));
    }

    static double recencyFactor(Instant lastScanAt, Instant now) {
        if (lastScanAt == null) {
            return 0.0;
        }
        long days = Math.max(0, Duration.between(lastScanAt, now).toDays());
        if (days >= RECENCY_HALF_LIFE_DAYS) {
            return 0.0;
        }
        return 1.0 - ((double) days / RECENCY_HALF_LIFE_DAYS);
    }

    static double activityFactor(int eventsSinceScan) {
        if (eventsSinceScan < 0) {
            return 1.0;
        }
        return Math.pow(0.5, eventsSinceScan / 2.0);
    }

    public static double sourceTrust(String eventType) {
        if (eventType == null) {
            return 0.3;
        }
        return switch (eventType) {
            case "STOCK_SCAN", "PO_RECEIVED" -> 0.9;
            case "STOCK_TRANSFER" -> 0.7;
            case "STOCK_ADJUSTMENT" -> 0.5;
            case "PART_USED" -> 0.3;
            default -> 0.3;
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
