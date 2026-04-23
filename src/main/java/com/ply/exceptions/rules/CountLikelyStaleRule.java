package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class CountLikelyStaleRule implements ExceptionRule {

    public static final String TYPE = "COUNT_LIKELY_STALE";
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.5;
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.7;

    private final ObjectMapper mapper;

    public CountLikelyStaleRule(ObjectMapper mapper) {
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
            if (ts.confidence() >= LOW_CONFIDENCE_THRESHOLD) {
                continue;
            }

            Optional<JobRiskView.LocationState> fallback = findNearestHighConfidence(item, truck);
            String suggested = fallback
                    .map(l -> "Pull from " + l.locationId() + " (confidence " + formatConfidence(l.confidence()) + ")")
                    .orElse("Cycle-count " + truck + " before dispatch");

            ObjectNode evidence = mapper.createObjectNode();
            evidence.put("location", truck);
            evidence.put("qty", ts.qty());
            evidence.put("confidence", round(ts.confidence()));
            evidence.put("events_since_scan", ts.eventsSinceScan());
            evidence.put("required_qty", item.required());
            if (ts.lastScanAt() != null) {
                evidence.put("last_scan_at", ts.lastScanAt().toString());
            } else {
                evidence.putNull("last_scan_at");
            }
            if (ts.lastEventType() != null) {
                evidence.put("last_event_type", ts.lastEventType());
            }

            String summary = "Count on " + truck + " for " + item.sku()
                    + " is low-confidence (" + formatConfidence(ts.confidence()) + ")";

            String dedupeKey = TYPE + ":" + view.job().id() + ":" + item.sku() + ":" + truck;

            out.add(new Candidate(
                    TYPE,
                    "medium",
                    "Count likely stale",
                    suggested,
                    summary,
                    view.job().id(),
                    item.sku(),
                    truck,
                    evidence,
                    dedupeKey
            ));
        }
        return out;
    }

    private static Optional<JobRiskView.LocationState> findNearestHighConfidence(
            JobRiskView.PerItem item, String excludeLocation) {
        return item.byLocation().stream()
                .filter(l -> !l.locationId().equals(excludeLocation))
                .filter(l -> l.confidence() >= HIGH_CONFIDENCE_THRESHOLD)
                .filter(l -> l.qty() >= item.required())
                .max(Comparator.comparingDouble(JobRiskView.LocationState::confidence));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String formatConfidence(double v) {
        return String.format("%.2f", v);
    }
}
