CREATE TABLE price_baseline (
    sku         TEXT NOT NULL REFERENCES items(sku),
    vendor      TEXT NOT NULL,
    mean        NUMERIC(12,4) NOT NULL DEFAULT 0,
    m2          NUMERIC(16,4) NOT NULL DEFAULT 0,
    samples     INTEGER NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (sku, vendor)
);
