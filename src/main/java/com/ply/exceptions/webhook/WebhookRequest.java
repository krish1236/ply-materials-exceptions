package com.ply.exceptions.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.ply.exceptions.events.EventType;

import java.time.Instant;

public record WebhookRequest(
        @JsonProperty("external_id") String externalId,
        @JsonProperty("type") EventType type,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("payload") JsonNode payload
) {}
