-- One evidence bundle per incident: a zip with the recent logs of the matching containers and the
-- ranked changes before the alert. At most a few MB each; kept 30 days by default.
CREATE TABLE evidence (
    incident_id  UUID PRIMARY KEY,
    captured_at  TIMESTAMPTZ NOT NULL,
    containers   INTEGER NOT NULL,
    log_lines    INTEGER NOT NULL,
    changes      INTEGER NOT NULL,
    likely_cause TEXT,
    bundle       BYTEA NOT NULL
);

CREATE INDEX evidence_captured_at ON evidence (captured_at);

-- Transactional outbox and processed events, as in every PageRewind service (libs/messaging).
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

CREATE TABLE processed_event (
    event_id     uuid        PRIMARY KEY,
    processed_at timestamptz NOT NULL
);
