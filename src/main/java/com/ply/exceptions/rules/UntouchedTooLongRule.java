package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class UntouchedTooLongRule implements ExceptionRule {

    public static final String TYPE = "UNTOUCHED_TOO_LONG";
    private static final long STALE_DAYS = 14;

    private final ObjectMapper mapper;

    public UntouchedTooLongRule(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return TYPE;
    }

    @Override
    public List<Candidate> evaluate(JobRiskView view) {
        List<Candidate> out = new ArrayList<>();
        String truck = view.job().assignedTruck();
        if (truck == null) {
            return out;
        }
        for (JobRiskView.PerItem item : view.perItem()) {
            Optional<JobRiskView.LocationState> truckState = item.byLocation().stream()
                    .filter(l -> truck.equals(l.locationId()))
                    .findFirst();
            if (truckState.isEmpty()) {
                continue;
            }
            JobRiskView.LocationState ts = truckState.get();
            if (ts.lastScanAt() == null) {
                out.add(build(view, item, ts, truck, Long.MAX_VALUE));
                continue;
            }
            long days = Math.max(0, Duration.between(ts.lastScanAt(), view.clock()).toDays());
            if (days >= STALE_DAYS) {
                out.add(build(view, item, ts, truck, days));
            }
        }
        return out;
    }

    private Candidate build(JobRiskView view, JobRiskView.PerItem item,
                            JobRiskView.LocationState ts, String truck, long days) {
        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("truck", truck);
        if (ts.lastScanAt() != null) {
            evidence.put("last_scan_at", ts.lastScanAt().toString());
            evidence.put("days_since_last_scan", days);
        } else {
            evidence.putNull("last_scan_at");
        }
        evidence.put("qty", ts.qty());

        String summary = ts.lastScanAt() == null
                ? truck + " has no physical scan on record for " + item.sku()
                : truck + " has not been scanned for " + item.sku() + " in " + days + " days";

        return new Candidate(
                TYPE,
                "low",
                "Untouched too long",
                "Cycle-count " + truck + " this week",
                summary,
                view.job().id(),
                item.sku(),
                truck,
                evidence,
                TYPE + ":" + view.job().id() + ":" + item.sku() + ":" + truck
        );
    }
}
