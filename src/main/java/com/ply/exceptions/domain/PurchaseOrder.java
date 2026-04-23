package com.ply.exceptions.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PurchaseOrder(
        String id,
        String vendor,
        Instant placedAt,
        Instant eta,
        String status,
        String linkedJobId,
        List<Line> lines
) {
    public record Line(String sku, int qty, BigDecimal unitCost) {}
}
