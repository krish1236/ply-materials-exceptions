package com.ply.exceptions.confidence;

import java.time.Instant;

public record ScoreInputs(
        Instant lastScanAt,
        int eventsSinceScan,
        String lastEventType,
        Instant now,
        double conflictPenalty
) {}
