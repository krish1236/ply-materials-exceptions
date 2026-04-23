package com.ply.exceptions.domain;

import java.time.Instant;
import java.util.List;

public record Job(
        String id,
        Instant scheduledFor,
        String customer,
        String assignedTech,
        String assignedTruck,
        String status,
        List<JobRequirement> requirements
) {
    public record JobRequirement(String sku, int required) {}
}
