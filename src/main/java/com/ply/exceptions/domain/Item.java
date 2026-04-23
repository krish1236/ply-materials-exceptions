package com.ply.exceptions.domain;

import java.math.BigDecimal;

public record Item(String sku, String name, BigDecimal unitCost) {}
