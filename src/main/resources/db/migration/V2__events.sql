CREATE TABLE events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source          TEXT NOT NULL,
    external_id     TEXT NOT NULL,
    type            TEXT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    payload         JSONB NOT NULL,
    UNIQUE (source, external_id)
);

CREATE INDEX events_occurred_at_idx ON events (occurred_at);
CREATE INDEX events_type_idx ON events (type);
