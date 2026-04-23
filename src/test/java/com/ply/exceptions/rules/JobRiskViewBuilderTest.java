package com.ply.exceptions.rules;

import com.ply.exceptions.PostgresTestBase;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestBase.class)
class JobRiskViewBuilderTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired JobRepository jobRepo;
    @Autowired JobRiskViewBuilder builder;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM stock_projection");
        jdbc.update("DELETE FROM price_baseline");
        jdbc.update("DELETE FROM purchase_order_lines");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("DELETE FROM job_requirements");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM locations");
        jdbc.update("DELETE FROM events");
        jdbc.update("UPDATE projector_offset SET last_ingested_at = '1970-01-01T00:00:00Z', last_event_id = '00000000-0000-0000-0000-000000000000'");

        jdbc.update("INSERT INTO items (sku, name) VALUES ('BRK-20A', '20A breaker')");
        jdbc.update("INSERT INTO locations (id, kind, label) VALUES " +
                "('wh_A', 'warehouse', 'WH A'), " +
                "('truck_8', 'truck', 'Truck 8')");

        jobRepo.upsert(new Job(
                "job_4721",
                Instant.parse("2026-04-23T08:00:00Z"),
                "Acme",
                "alex",
                "truck_8",
                "scheduled",
                List.of(new Job.JobRequirement("BRK-20A", 2))
        ));
    }

    @Test
    void build_pulls_stock_projection_rows_and_scores_confidence() {
        Instant now = Instant.parse("2026-04-22T10:00:00Z");
        jdbc.update("""
                INSERT INTO stock_projection
                    (sku, location_id, qty, last_event_at, last_scan_at, last_event_source,
                     last_event_type, events_since_scan)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "BRK-20A", "truck_8", 2,
                Timestamp.from(now.minus(3, ChronoUnit.DAYS)),
                Timestamp.from(now.minus(3, ChronoUnit.DAYS)),
                "fsm", "PART_USED", 4);
        jdbc.update("""
                INSERT INTO stock_projection
                    (sku, location_id, qty, last_event_at, last_scan_at, last_event_source,
                     last_event_type, events_since_scan)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "BRK-20A", "wh_A", 10,
                Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
                Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
                "fsm", "STOCK_SCAN", 0);

        Job job = jobRepo.findById("job_4721").orElseThrow();
        JobRiskView view = builder.build(job, now);

        assertThat(view.perItem()).hasSize(1);
        JobRiskView.PerItem item = view.perItem().get(0);
        assertThat(item.sku()).isEqualTo("BRK-20A");
        assertThat(item.required()).isEqualTo(2);
        assertThat(item.byLocation()).hasSize(2);

        JobRiskView.LocationState truck = item.byLocation().stream()
                .filter(l -> l.locationId().equals("truck_8"))
                .findFirst().orElseThrow();
        JobRiskView.LocationState wh = item.byLocation().stream()
                .filter(l -> l.locationId().equals("wh_A"))
                .findFirst().orElseThrow();

        assertThat(truck.qty()).isEqualTo(2);
        assertThat(wh.qty()).isEqualTo(10);
        assertThat(truck.confidence()).isLessThan(wh.confidence());
    }

    @Test
    void build_attaches_linked_purchase_orders_when_sku_matches() {
        Instant now = Instant.parse("2026-04-22T10:00:00Z");
        jdbc.update("""
                INSERT INTO purchase_orders (id, vendor, placed_at, eta, status, linked_job_id)
                VALUES ('po_1', 'Ferguson', ?, ?, 'placed', 'job_4721')
                """,
                Timestamp.from(now.minus(1, ChronoUnit.DAYS)),
                Timestamp.from(now.plus(1, ChronoUnit.DAYS)));
        jdbc.update("""
                INSERT INTO purchase_order_lines (po_id, sku, qty, unit_cost)
                VALUES ('po_1', 'BRK-20A', 10, ?)
                """,
                new BigDecimal("14.50"));

        Job job = jobRepo.findById("job_4721").orElseThrow();
        JobRiskView view = builder.build(job, now);

        JobRiskView.PerItem item = view.perItem().get(0);
        assertThat(item.openPos()).hasSize(1);
        assertThat(item.openPos().get(0).id()).isEqualTo("po_1");
    }
}
