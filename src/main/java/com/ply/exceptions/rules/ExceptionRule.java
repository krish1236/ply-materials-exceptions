package com.ply.exceptions.rules;

import java.util.List;

public interface ExceptionRule {
    String name();
    List<Candidate> evaluate(JobRiskView view);
}
