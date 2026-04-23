CREATE TABLE stock_projection (
    sku                 TEXT NOT NULL REFERENCES items(sku),
    location_id         TEXT NOT NULL REFERENCES locations(id),
    qty                 INTEGER NOT NULL DEFAULT 0,
    last_event_at       TIMESTAMPTZ,
    last_scan_at        TIMESTAMPTZ,
    last_event_source   TEXT,
    last_event_type     TEXT,
    events_since_scan   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (sku, location_id)
);

CREATE INDEX stock_projection_location_idx ON stock_projection (location_id);
CREATE INDEX stock_projection_last_scan_idx ON stock_projection (last_scan_at);

CREATE TABLE projector_offset (
    name                TEXT PRIMARY KEY,
    last_ingested_at    TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01T00:00:00Z'::timestamptz,
    last_event_id       UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'::uuid
);

INSERT INTO projector_offset (name) VALUES ('stock');
