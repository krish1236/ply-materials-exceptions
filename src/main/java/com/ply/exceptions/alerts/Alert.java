package com.ply.exceptions.alerts;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record Alert(
        UUID id,
        String type,
        String severity,
        String probableCause,
        String suggestedAction,
        String summary,
        String affectedJobId,
        String affectedSku,
        String affectedLocation,
        JsonNode evidence,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastSeenAt,
        Instant resolvedAt,
        String dedupeKey
) {}
