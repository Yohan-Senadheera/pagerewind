-- What changed in the cluster, for the "what changed" timeline. Kept for a limited time (7 days by default).
CREATE TABLE change_event (
    id          UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    kind        TEXT NOT NULL,
    namespace   TEXT,
    name        TEXT NOT NULL,
    workload    TEXT,
    summary     TEXT NOT NULL,
    details     JSONB NOT NULL DEFAULT '{}'
);

CREATE INDEX change_event_occurred_at ON change_event (occurred_at);
CREATE INDEX change_event_namespace_time ON change_event (namespace, occurred_at);
