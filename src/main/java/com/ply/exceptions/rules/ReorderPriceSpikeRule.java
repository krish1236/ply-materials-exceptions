package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ReorderPriceSpikeRule implements ExceptionRule {

    public static final String TYPE = "REORDER_PRICE_SPIKE";
    private static final double Z_THRESHOLD = 2.0;
    private static final int MIN_SAMPLES = 5;

    private final ObjectMapper mapper;

    public ReorderPriceSpikeRule(ObjectMapper mapper) {
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
            JobRiskView.PriceSignal ps = item.priceSignal();
            if (ps == null || ps.samples() < MIN_SAMPLES || ps.stddev() <= 0) {
                continue;
            }
            double z = (ps.latestQuote() - ps.mean()) / ps.stddev();
            if (z < Z_THRESHOLD) {
                continue;
            }

            ObjectNode evidence = mapper.createObjectNode();
            evidence.put("latest_quote", round(ps.latestQuote()));
            evidence.put("baseline_mean", round(ps.mean()));
            evidence.put("baseline_stddev", round(ps.stddev()));
            evidence.put("z_score", round(z));
            evidence.put("samples", ps.samples());

            String summary = "Latest quote " + format(ps.latestQuote())
                    + " is " + String.format("%.1f", z) + " stddev above baseline "
                    + format(ps.mean());

            out.add(new Candidate(
                    TYPE,
                    "medium",
                    "Reorder price spike",
                    "Request alternate vendor quote before approving next PO for " + item.sku(),
                    summary,
                    view.job().id(),
                    item.sku(),
                    null,
                    evidence,
                    TYPE + ":" + view.job().id() + ":" + item.sku()
            ));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String format(double v) {
        return "$" + String.format("%.2f", v);
    }
}
