CREATE TABLE items (
    sku         TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    unit_cost   NUMERIC(10,2)
);

CREATE TABLE locations (
    id      TEXT PRIMARY KEY,
    kind    TEXT NOT NULL CHECK (kind IN ('truck', 'warehouse', 'jobsite')),
    label   TEXT NOT NULL
);

CREATE TABLE jobs (
    id              TEXT PRIMARY KEY,
    scheduled_for   TIMESTAMPTZ NOT NULL,
    customer        TEXT NOT NULL,
    assigned_tech   TEXT,
    assigned_truck  TEXT REFERENCES locations(id),
    status          TEXT NOT NULL DEFAULT 'scheduled'
                    CHECK (status IN ('scheduled', 'in_progress', 'completed', 'cancelled'))
);

CREATE INDEX jobs_scheduled_for_idx ON jobs (scheduled_for);
CREATE INDEX jobs_status_idx ON jobs (status);

CREATE TABLE job_requirements (
    job_id      TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    sku         TEXT NOT NULL REFERENCES items(sku),
    required    INTEGER NOT NULL CHECK (required > 0),
    PRIMARY KEY (job_id, sku)
);
