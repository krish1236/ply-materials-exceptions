CREATE TABLE alerts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type                TEXT NOT NULL,
    severity            TEXT NOT NULL CHECK (severity IN ('low', 'medium', 'high')),
    probable_cause      TEXT NOT NULL,
    suggested_action    TEXT NOT NULL,
    summary             TEXT NOT NULL,
    affected_job_id     TEXT,
    affected_sku        TEXT,
    affected_location   TEXT,
    evidence            JSONB NOT NULL,
    status              TEXT NOT NULL DEFAULT 'open'
                        CHECK (status IN ('open', 'acknowledged', 'resolved', 'auto_resolved')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ,
    dedupe_key          TEXT NOT NULL UNIQUE
);

CREATE INDEX alerts_status_idx ON alerts (status);
CREATE INDEX alerts_created_at_idx ON alerts (created_at DESC);
CREATE INDEX alerts_job_id_idx ON alerts (affected_job_id);
