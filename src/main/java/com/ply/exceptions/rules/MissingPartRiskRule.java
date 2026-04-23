package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ply.exceptions.domain.PurchaseOrder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class MissingPartRiskRule implements ExceptionRule {

    public static final String TYPE = "MISSING_PART_RISK";

    private final ObjectMapper mapper;

    public MissingPartRiskRule(ObjectMapper mapper) {
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
            int truckQty = item.byLocation().stream()
                    .filter(l -> truck.equals(l.locationId()))
                    .mapToInt(JobRiskView.LocationState::qty)
                    .findFirst()
                    .orElse(0);
            int shortage = item.required() - truckQty;
            if (shortage <= 0) {
                continue;
            }

            boolean coveredByPo = item.openPos().stream()
                    .anyMatch(po -> !po.eta().isAfter(view.job().scheduledFor())
                            && !"received".equals(po.status()));
            if (coveredByPo) {
                continue;
            }

            Optional<JobRiskView.LocationState> alt = item.byLocation().stream()
                    .filter(l -> !truck.equals(l.locationId()))
                    .filter(l -> l.qty() >= shortage)
                    .max(Comparator.comparingDouble(JobRiskView.LocationState::confidence));

            ObjectNode evidence = mapper.createObjectNode();
            evidence.put("truck", truck);
            evidence.put("truck_qty", truckQty);
            evidence.put("required_qty", item.required());
            evidence.put("shortage", shortage);

            String suggested;
            if (alt.isPresent()) {
                suggested = "Pull " + shortage + " " + item.sku() + " from " + alt.get().locationId()
                        + " before dispatch";
                evidence.put("pull_from", alt.get().locationId());
                evidence.put("pull_from_qty", alt.get().qty());
            } else if (!item.openPos().isEmpty()) {
                PurchaseOrder soonest = item.openPos().stream()
                        .min(Comparator.comparing(PurchaseOrder::eta))
                        .orElseThrow();
                suggested = "Expedite " + soonest.id() + " from " + soonest.vendor();
                evidence.put("suggested_po", soonest.id());
            } else {
                suggested = "Emergency order " + shortage + " " + item.sku() + " now";
            }

            String summary = truck + " short " + shortage + " " + item.sku()
                    + " (required " + item.required() + ", on hand " + truckQty + ")";

            out.add(new Candidate(
                    TYPE,
                    "high",
                    "Missing part risk",
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
}
