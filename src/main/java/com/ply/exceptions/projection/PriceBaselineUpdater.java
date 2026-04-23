package com.ply.exceptions.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.ply.exceptions.events.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class PriceBaselineUpdater implements ProjectionUpdater {

    private final JdbcTemplate jdbc;

    public PriceBaselineUpdater(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(EventEnvelope env) {
        if (env.type() != com.ply.exceptions.events.EventType.PRICE_QUOTED) {
            return;
        }
        JsonNode p = env.payload();
        String sku = text(p, "sku");
        String vendor = text(p, "vendor");
        BigDecimal unitCost = new BigDecimal(text(p, "unit_cost"));

        applyWelford(sku, vendor, unitCost.doubleValue());
    }

    private void applyWelford(String sku, String vendor, double x) {
        List<double[]> current = jdbc.query("""
                SELECT mean, m2, samples FROM price_baseline WHERE sku = ? AND vendor = ?
                """,
                (rs, i) -> new double[]{
                        rs.getBigDecimal("mean").doubleValue(),
                        rs.getBigDecimal("m2").doubleValue(),
                        rs.getInt("samples")
                },
                sku, vendor);

        double mean;
        double m2;
        int samples;
        if (current.isEmpty()) {
            mean = 0.0;
            m2 = 0.0;
            samples = 0;
        } else {
            mean = current.get(0)[0];
            m2 = current.get(0)[1];
            samples = (int) current.get(0)[2];
        }

        int newN = samples + 1;
        double delta = x - mean;
        double newMean = mean + delta / newN;
        double delta2 = x - newMean;
        double newM2 = m2 + delta * delta2;

        jdbc.update("""
                INSERT INTO price_baseline (sku, vendor, mean, m2, samples, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT (sku, vendor) DO UPDATE SET
                    mean = EXCLUDED.mean,
                    m2 = EXCLUDED.m2,
                    samples = EXCLUDED.samples,
                    updated_at = EXCLUDED.updated_at
                """,
                sku, vendor,
                BigDecimal.valueOf(newMean),
                BigDecimal.valueOf(newM2),
                newN);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return v.asText();
    }
}
