package com.ply.exceptions.rules;

import com.fasterxml.jackson.databind.JsonNode;

public record Candidate(
        String type,
        String severity,
        String probableCause,
        String suggestedAction,
        String summary,
        String jobId,
        String sku,
        String locationId,
        JsonNode evidence,
        String dedupeKey
) {}
