CREATE TABLE dead_letters (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source          TEXT NOT NULL,
    reason          TEXT NOT NULL,
    raw_body        TEXT NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX dead_letters_received_idx ON dead_letters (received_at DESC);
