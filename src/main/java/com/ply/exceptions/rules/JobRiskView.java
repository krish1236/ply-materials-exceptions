package com.ply.exceptions.rules;

import com.ply.exceptions.domain.Job;
import com.ply.exceptions.domain.PurchaseOrder;

import java.time.Instant;
import java.util.List;

public record JobRiskView(
        Job job,
        List<PerItem> perItem,
        Instant clock
) {

    public record PerItem(
            String sku,
            int required,
            List<LocationState> byLocation,
            List<PurchaseOrder> openPos
    ) {}

    public record LocationState(
            String locationId,
            int qty,
            double confidence,
            Instant lastScanAt,
            int eventsSinceScan,
            String lastEventType
    ) {}
}
