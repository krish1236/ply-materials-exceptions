package com.ply.exceptions.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ply.exceptions.alerts.AlertService;
import com.ply.exceptions.domain.Item;
import com.ply.exceptions.domain.ItemRepository;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.JobRepository;
import com.ply.exceptions.domain.Location;
import com.ply.exceptions.domain.LocationRepository;
import com.ply.exceptions.events.EventEnvelope;
import com.ply.exceptions.events.EventStore;
import com.ply.exceptions.events.EventType;
import com.ply.exceptions.projection.Projector;
import com.ply.exceptions.rules.JobRiskView;
import com.ply.exceptions.rules.JobRiskViewBuilder;
import com.ply.exceptions.rules.RulesEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("dev")
public class DemoScenarioSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoScenarioSeeder.class);

    private final ItemRepository items;
    private final LocationRepository locations;
    private final JobRepository jobs;
    private final EventStore events;
    private final Projector projector;
    private final JobRiskViewBuilder viewBuilder;
    private final RulesEngine rules;
    private final AlertService alerts;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    private final AtomicInteger seq = new AtomicInteger();

    public DemoScenarioSeeder(ItemRepository items,
                              LocationRepository locations,
                              JobRepository jobs,
                              EventStore events,
                              Projector projector,
                              JobRiskViewBuilder viewBuilder,
                              RulesEngine rules,
                              AlertService alerts,
                              ObjectMapper mapper,
                              JdbcTemplate jdbc,
                              Clock clock) {
        this.items = items;
        this.locations = locations;
        this.jobs = jobs;
        this.events = events;
        this.projector = projector;
        this.viewBuilder = viewBuilder;
        this.rules = rules;
        this.alerts = alerts;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (events.count() > 0) {
            log.info("events already seeded, skipping scenario");
            return;
        }

        log.info("seeding demo scenario");
        Instant now = clock.instant();

        seedCatalog();
        seedJobs(now);
        seedEvents(now);

        projector.pumpOnce();
        runRules(now);

        log.info("demo scenario ready: events={} alerts={}",
                events.count(), alerts.findAll().size());
    }

    private void seedCatalog() {
        items.upsert(new Item("BRK-20A", "20A breaker", new BigDecimal("14.50")));
        items.upsert(new Item("BRK-30A", "30A breaker", new BigDecimal("18.25")));
        items.upsert(new Item("CAP-45", "45uF capacitor", new BigDecimal("22.80")));
        items.upsert(new Item("THERM-PRO", "Programmable thermostat", new BigDecimal("128.00")));
        items.upsert(new Item("WIRE-12AWG", "12AWG wire spool", new BigDecimal("64.00")));

        locations.upsert(new Location("wh_A", "warehouse", "Warehouse A (HQ)"));
        locations.upsert(new Location("truck_8", "truck", "Truck 8 (Alex)"));
        locations.upsert(new Location("truck_12", "truck", "Truck 12 (Jordan)"));
        locations.upsert(new Location("truck_22", "truck", "Truck 22 (Sam)"));
    }

    private void seedJobs(Instant now) {
        Instant morning = now.plus(Duration.ofDays(1))
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofHours(8));

        record Spec(int dayOffset, String id, String truck, String tech, String customer,
                    List<Job.JobRequirement> reqs) {}

        List<Spec> specs = List.of(
                new Spec(0, "job_5001", "truck_8",  "alex",   "Acme Offices",
                        List.of(new Job.JobRequirement("BRK-20A", 2),
                                new Job.JobRequirement("CAP-45", 1))),
                new Spec(1, "job_5002", "truck_12", "jordan", "Oak Street Residence",
                        List.of(new Job.JobRequirement("WIRE-12AWG", 2))),
                new Spec(2, "job_5003", "truck_22", "sam",    "Northside Industrial",
                        List.of(new Job.JobRequirement("BRK-30A", 3),
                                new Job.JobRequirement("THERM-PRO", 1))),
                new Spec(3, "job_5004", "truck_8",  "alex",   "Pine Valley Condo",
                        List.of(new Job.JobRequirement("BRK-30A", 2))),
                new Spec(4, "job_5005", "truck_12", "jordan", "Hudson Heights",
                        List.of(new Job.JobRequirement("BRK-20A", 2),
                                new Job.JobRequirement("CAP-45", 1))),
                new Spec(5, "job_5006", "truck_22", "sam",    "Astoria Commercial",
                        List.of(new Job.JobRequirement("WIRE-12AWG", 2))),
                new Spec(6, "job_5007", "truck_8",  "alex",   "Brooklyn Brownstone",
                        List.of(new Job.JobRequirement("THERM-PRO", 1))),
                new Spec(7, "job_5008", "truck_12", "jordan", "Midtown Deli",
                        List.of(new Job.JobRequirement("BRK-30A", 2))),
                new Spec(8, "job_5009", "truck_22", "sam",    "Bronx Warehouse",
                        List.of(new Job.JobRequirement("CAP-45", 2))),
                new Spec(9, "job_5010", "truck_8",  "alex",   "Staten Island Diner",
                        List.of(new Job.JobRequirement("BRK-30A", 2),
                                new Job.JobRequirement("CAP-45", 1)))
        );

        for (Spec s : specs) {
            Instant scheduled = morning.plus(Duration.ofDays(s.dayOffset()));
            jobs.upsert(new Job(s.id(), scheduled, s.customer(), s.tech(), s.truck(),
                    "scheduled", s.reqs()));
        }
    }

    private void seedEvents(Instant now) {
        Instant oneMonthAgo = now.minus(Duration.ofDays(30));

        scan(oneMonthAgo, "wh_A", "BRK-20A", 40);
        scan(oneMonthAgo, "wh_A", "BRK-30A", 30);
        scan(oneMonthAgo, "wh_A", "CAP-45", 15);
        scan(oneMonthAgo, "wh_A", "THERM-PRO", 8);
        scan(oneMonthAgo, "wh_A", "WIRE-12AWG", 10);

        Instant d = now.minus(Duration.ofDays(14));
        scan(d, "truck_8", "BRK-20A", 6);
        scan(d, "truck_8", "CAP-45", 3);
        scan(d, "truck_12", "BRK-20A", 4);
        scan(d, "truck_12", "WIRE-12AWG", 4);
        scan(d, "truck_22", "BRK-30A", 1);
        scan(d, "truck_22", "THERM-PRO", 0);

        Instant fiveDaysAgo = now.minus(Duration.ofDays(5));
        partUsed(fiveDaysAgo, "truck_8", "BRK-20A", 1, "alex", "job_4600");
        partUsed(fiveDaysAgo.plus(Duration.ofHours(3)), "truck_8", "BRK-20A", 1, "alex", "job_4601");

        Instant threeDaysAgo = now.minus(Duration.ofDays(3));
        partUsed(threeDaysAgo, "truck_8", "CAP-45", 1, "alex", "job_4650");
        partUsed(threeDaysAgo.plus(Duration.ofHours(4)), "truck_8", "CAP-45", 1, "alex", "job_4651");

        Instant oneDayAgo = now.minus(Duration.ofDays(1));
        partUsed(oneDayAgo, "truck_22", "BRK-30A", 1, "sam", "job_4700");
        adjustment(oneDayAgo.plus(Duration.ofHours(1)), "truck_22", "BRK-30A", -1, "reconcile");

        for (int i = 0; i < 10; i++) {
            Instant at = now.minus(Duration.ofDays(20 - i));
            double cost = 14.40 + (i % 3) * 0.10;
            priceQuoted(at, "BRK-30A", "Ferguson NYC", cost);
        }
        priceQuoted(now.minus(Duration.ofHours(2)), "BRK-30A", "Ferguson NYC", 19.20);

        poPlaced(now.minus(Duration.ofDays(2)),
                "po_8841", "Ferguson NYC",
                now.plus(Duration.ofDays(15)),
                "job_5003",
                "BRK-30A", 10, 14.50);
    }

    private void runRules(Instant now) {
        List<JobRiskView> views = viewBuilder.buildForScheduledBetween(
                now, now.plus(Duration.ofDays(14)), now);
        alerts.processEvaluation(rules.evaluateAll(views));
    }

    private void scan(Instant at, String location, String sku, int qty) {
        ObjectNode p = mapper.createObjectNode()
                .put("sku", sku).put("location", location).put("qty", qty);
        append(at, EventType.STOCK_SCAN, p, "fsm");
    }

    private void partUsed(Instant at, String fromLocation, String sku, int qty, String tech, String jobId) {
        ObjectNode p = mapper.createObjectNode()
                .put("sku", sku).put("qty", qty)
                .put("from_location", fromLocation)
                .put("tech_id", tech)
                .put("job_id", jobId);
        append(at, EventType.PART_USED, p, "fsm");
    }

    private void adjustment(Instant at, String location, String sku, int delta, String reason) {
        ObjectNode p = mapper.createObjectNode()
                .put("sku", sku).put("location", location)
                .put("delta", delta).put("reason", reason);
        append(at, EventType.STOCK_ADJUSTMENT, p, "fsm");
    }

    private void priceQuoted(Instant at, String sku, String vendor, double unitCost) {
        ObjectNode p = mapper.createObjectNode()
                .put("sku", sku).put("vendor", vendor)
                .put("unit_cost", String.valueOf(unitCost));
        append(at, EventType.PRICE_QUOTED, p, "acc");
    }

    private void poPlaced(Instant at, String poId, String vendor, Instant eta, String linkedJobId,
                          String sku, int qty, double unitCost) {
        ObjectNode line = mapper.createObjectNode()
                .put("sku", sku).put("qty", qty).put("unit_cost", String.valueOf(unitCost));
        ObjectNode p = mapper.createObjectNode()
                .put("po_id", poId).put("vendor", vendor)
                .put("placed_at", at.toString()).put("eta", eta.toString())
                .put("linked_job_id", linkedJobId);
        p.putArray("lines").add(line);
        append(at, EventType.PO_PLACED, p, "acc");
    }

    private void append(Instant at, EventType type, ObjectNode payload, String source) {
        int n = seq.incrementAndGet();
        events.append(new EventEnvelope(source, "seed_evt_" + n, type, at, payload));
    }
}
