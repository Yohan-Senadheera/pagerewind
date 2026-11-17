CREATE TABLE incident (
    id              uuid        PRIMARY KEY,
    fingerprint     text        NOT NULL,
    service         text        NOT NULL,
    severity        text        NOT NULL CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO')),
    summary         text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED')),
    alert_count     integer     NOT NULL DEFAULT 0 CHECK (alert_count >= 0),
    labels          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    acknowledged_at timestamptz,
    resolved_at     timestamptz,
    version         bigint      NOT NULL DEFAULT 0
);

-- At most one open incident per fingerprint. This is the real duplicate guarantee: concurrent
-- inserts for the same fingerprint wait for each other and only one row survives.
CREATE UNIQUE INDEX incident_one_open_per_fingerprint
    ON incident (fingerprint)
    WHERE status <> 'RESOLVED';

CREATE INDEX incident_status_created_idx ON incident (status, created_at DESC);
CREATE INDEX incident_created_idx ON incident (created_at DESC);

CREATE TABLE incident_event (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id uuid        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    type        text        NOT NULL,
    message     text        NOT NULL,
    actor       text,
    data        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX incident_event_incident_idx ON incident_event (incident_id, id);

-- Transactional outbox: events are written here in the same transaction as the state change,
-- then a relay publishes them to RabbitMQ.
CREATE TABLE outbox (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     uuid        NOT NULL UNIQUE,
    event_type   text        NOT NULL,
    routing_key  text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz,
    attempts     integer     NOT NULL DEFAULT 0,
    last_error   text
);

CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

-- Events this service has already handled. Inserting the event id in the same transaction as
-- the work makes every consumer idempotent.
CREATE TABLE processed_event (
    event_id     uuid        PRIMARY KEY,
    processed_at timestamptz NOT NULL
);
