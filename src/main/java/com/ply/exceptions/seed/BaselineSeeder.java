package com.ply.exceptions.seed;

import com.ply.exceptions.domain.Item;
import com.ply.exceptions.domain.ItemRepository;
import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.JobRepository;
import com.ply.exceptions.domain.Location;
import com.ply.exceptions.domain.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@Profile("dev")
public class BaselineSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaselineSeeder.class);

    private final ItemRepository items;
    private final LocationRepository locations;
    private final JobRepository jobs;

    public BaselineSeeder(ItemRepository items, LocationRepository locations, JobRepository jobs) {
        this.items = items;
        this.locations = locations;
        this.jobs = jobs;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (!items.findAll().isEmpty()) {
            log.info("baseline already seeded, skipping");
            return;
        }

        log.info("seeding baseline items, locations, jobs");

        items.upsert(new Item("BRK-20A", "20A breaker", new BigDecimal("14.50")));
        items.upsert(new Item("BRK-30A", "30A breaker", new BigDecimal("18.25")));
        items.upsert(new Item("CAP-45", "45uF capacitor", new BigDecimal("22.80")));
        items.upsert(new Item("THERM-PRO", "Programmable thermostat", new BigDecimal("128.00")));

        locations.upsert(new Location("wh_A", "warehouse", "Warehouse A (HQ)"));
        locations.upsert(new Location("truck_8", "truck", "Truck 8 (Alex)"));
        locations.upsert(new Location("truck_12", "truck", "Truck 12 (Jordan)"));

        Instant tomorrow8am = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        jobs.upsert(new Job(
                "job_4721",
                tomorrow8am,
                "Acme Offices",
                "alex",
                "truck_8",
                "scheduled",
                List.of(
                        new Job.JobRequirement("BRK-20A", 2),
                        new Job.JobRequirement("CAP-45", 1)
                )
        ));
        jobs.upsert(new Job(
                "job_4722",
                tomorrow8am.plus(2, ChronoUnit.HOURS),
                "Oak Street Residence",
                "jordan",
                "truck_12",
                "scheduled",
                List.of(new Job.JobRequirement("THERM-PRO", 1))
        ));
    }
}
