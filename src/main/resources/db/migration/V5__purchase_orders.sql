CREATE TABLE purchase_orders (
    id              TEXT PRIMARY KEY,
    vendor          TEXT NOT NULL,
    placed_at       TIMESTAMPTZ NOT NULL,
    eta             TIMESTAMPTZ NOT NULL,
    status          TEXT NOT NULL DEFAULT 'placed'
                    CHECK (status IN ('placed', 'in_transit', 'received', 'cancelled')),
    linked_job_id   TEXT REFERENCES jobs(id)
);

CREATE INDEX purchase_orders_eta_idx ON purchase_orders (eta);
CREATE INDEX purchase_orders_linked_job_idx ON purchase_orders (linked_job_id);

CREATE TABLE purchase_order_lines (
    po_id       TEXT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    sku         TEXT NOT NULL REFERENCES items(sku),
    qty         INTEGER NOT NULL CHECK (qty > 0),
    unit_cost   NUMERIC(10,2) NOT NULL,
    PRIMARY KEY (po_id, sku)
);
