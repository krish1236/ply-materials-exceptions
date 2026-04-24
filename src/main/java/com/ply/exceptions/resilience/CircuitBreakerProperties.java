package com.ply.exceptions.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ply.circuit-breaker")
public record CircuitBreakerProperties(
        int failureThreshold,
        long cooldownSeconds
) {}
