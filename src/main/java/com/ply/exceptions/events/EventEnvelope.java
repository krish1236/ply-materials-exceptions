package com.ply.exceptions.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record EventEnvelope(
        String source,
        String externalId,
        EventType type,
        Instant occurredAt,
        JsonNode payload
) {}
