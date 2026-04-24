CREATE TABLE circuit_state (
    source                  TEXT PRIMARY KEY,
    consecutive_failures    INTEGER NOT NULL DEFAULT 0,
    opened_at               TIMESTAMPTZ,
    last_failure_at         TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
