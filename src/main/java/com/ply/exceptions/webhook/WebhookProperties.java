package com.ply.exceptions.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ply.webhook")
public record WebhookProperties(
        String signingSecret,
        long maxClockSkewSeconds
) {}
