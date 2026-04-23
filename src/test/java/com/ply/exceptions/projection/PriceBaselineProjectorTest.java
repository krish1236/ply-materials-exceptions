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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Import(PostgresTestBase.class)
class PriceBaselineProjectorTest {

    @Autowired EventStore store;
    @Autowired Projector projector;
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
    }

    @Test
    void ten_quotes_produce_baseline_with_non_zero_samples() {
        double[] quotes = {14.0, 14.5, 14.2, 14.8, 14.1, 14.6, 14.3, 14.9, 14.0, 14.7};
        Instant t = Instant.parse("2026-04-22T10:00:00Z");

        for (int i = 0; i < quotes.length; i++) {
            var payload = mapper.createObjectNode()
                    .put("sku", "BRK-20A")
                    .put("vendor", "Ferguson NYC")
                    .put("unit_cost", String.valueOf(quotes[i]));
            store.append(new EventEnvelope("acc", "price_evt_" + i, EventType.PRICE_QUOTED,
                    t.plusSeconds(i * 60L), payload));
        }

        projector.pumpOnce();

        Integer samples = jdbc.queryForObject(
                "SELECT samples FROM price_baseline WHERE sku = 'BRK-20A' AND vendor = 'Ferguson NYC'",
                Integer.class);
        BigDecimal mean = jdbc.queryForObject(
                "SELECT mean FROM price_baseline WHERE sku = 'BRK-20A' AND vendor = 'Ferguson NYC'",
                BigDecimal.class);

        assertThat(samples).isEqualTo(10);
        double sum = 0;
        for (double q : quotes) sum += q;
        double expectedMean = sum / quotes.length;
        assertThat(mean.setScale(3, RoundingMode.HALF_UP).doubleValue())
                .isCloseTo(expectedMean, within(0.005));
    }

    @Test
    void first_quote_sets_mean_and_samples_to_one() {
        var payload = mapper.createObjectNode()
                .put("sku", "BRK-20A")
                .put("vendor", "Ferguson NYC")
                .put("unit_cost", "12.50");
        store.append(new EventEnvelope("acc", "price_first", EventType.PRICE_QUOTED,
                Instant.parse("2026-04-22T10:00:00Z"), payload));

        projector.pumpOnce();

        Integer samples = jdbc.queryForObject(
                "SELECT samples FROM price_baseline WHERE sku = 'BRK-20A' AND vendor = 'Ferguson NYC'",
                Integer.class);
        BigDecimal mean = jdbc.queryForObject(
                "SELECT mean FROM price_baseline WHERE sku = 'BRK-20A' AND vendor = 'Ferguson NYC'",
                BigDecimal.class);

        assertThat(samples).isEqualTo(1);
        assertThat(mean.setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("12.50"));
    }
}
