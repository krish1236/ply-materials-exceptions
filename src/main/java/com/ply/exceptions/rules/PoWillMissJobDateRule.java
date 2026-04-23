package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ply.exceptions.domain.PurchaseOrder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PoWillMissJobDateRule implements ExceptionRule {

    public static final String TYPE = "PO_WILL_MISS_JOB_DATE";

    private final ObjectMapper mapper;

    public PoWillMissJobDateRule(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return TYPE;
    }

    @Override
    public List<Candidate> evaluate(JobRiskView view) {
        List<Candidate> out = new ArrayList<>();
        for (JobRiskView.PerItem item : view.perItem()) {
            for (PurchaseOrder po : item.openPos()) {
                if ("received".equals(po.status()) || "cancelled".equals(po.status())) {
                    continue;
                }
                if (!po.eta().isAfter(view.job().scheduledFor())) {
                    continue;
                }

                ObjectNode evidence = mapper.createObjectNode();
                evidence.put("po_id", po.id());
                evidence.put("vendor", po.vendor());
                evidence.put("po_eta", po.eta().toString());
                evidence.put("job_scheduled_for", view.job().scheduledFor().toString());
                evidence.put("po_status", po.status());

                String summary = "PO " + po.id() + " eta " + po.eta()
                        + " lands after job " + view.job().id() + " on " + view.job().scheduledFor();

                out.add(new Candidate(
                        TYPE,
                        "high",
                        "PO will miss job date",
                        "Expedite " + po.id() + " from " + po.vendor()
                                + " or pull " + item.sku() + " from warehouse",
                        summary,
                        view.job().id(),
                        item.sku(),
                        null,
                        evidence,
                        TYPE + ":" + view.job().id() + ":" + item.sku() + ":" + po.id()
                ));
            }
        }
        return out;
    }
}
