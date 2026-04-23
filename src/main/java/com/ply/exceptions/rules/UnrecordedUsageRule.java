package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class UnrecordedUsageRule implements ExceptionRule {

    public static final String TYPE = "UNRECORDED_USAGE_LIKELY";
    private static final int MIN_EVENTS_SINCE_SCAN = 2;
    private static final double MAX_CONFIDENCE = 0.6;

    private final ObjectMapper mapper;

    public UnrecordedUsageRule(ObjectMapper mapper) {
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
            if (!"PART_USED".equals(ts.lastEventType())) {
                continue;
            }
            if (ts.eventsSinceScan() < MIN_EVENTS_SINCE_SCAN) {
                continue;
            }
            if (ts.confidence() > MAX_CONFIDENCE) {
                continue;
            }

            ObjectNode evidence = mapper.createObjectNode();
            evidence.put("truck", truck);
            evidence.put("events_since_scan", ts.eventsSinceScan());
            evidence.put("last_event_type", ts.lastEventType());
            evidence.put("confidence", round(ts.confidence()));
            evidence.put("required_qty", item.required());

            String tech = view.job().assignedTech();
            String suggested = tech != null
                    ? "Call " + tech + " to confirm " + item.sku() + " count before dispatch"
                    : "Confirm " + item.sku() + " count with tech before dispatch";

            String summary = ts.eventsSinceScan()
                    + " usage events on " + truck + " for " + item.sku()
                    + " with no scan-out";

            out.add(new Candidate(
                    TYPE,
                    "high",
                    "Unrecorded usage likely",
                    suggested,
                    summary,
                    view.job().id(),
                    item.sku(),
                    truck,
                    evidence,
                    TYPE + ":" + view.job().id() + ":" + item.sku() + ":" + truck
            ));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
