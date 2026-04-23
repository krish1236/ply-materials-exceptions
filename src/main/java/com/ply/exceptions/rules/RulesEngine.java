package com.ply.exceptions.rules;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RulesEngine {

    private final List<ExceptionRule> rules;

    public RulesEngine(List<ExceptionRule> rules) {
        this.rules = rules;
    }

    public List<Candidate> evaluate(JobRiskView view) {
        List<Candidate> out = new ArrayList<>();
        for (ExceptionRule r : rules) {
            out.addAll(r.evaluate(view));
        }
        return out;
    }

    public List<Candidate> evaluateAll(List<JobRiskView> views) {
        List<Candidate> out = new ArrayList<>();
        for (JobRiskView v : views) {
            out.addAll(evaluate(v));
        }
        return out;
    }

    public List<ExceptionRule> rules() {
        return rules;
    }
}
