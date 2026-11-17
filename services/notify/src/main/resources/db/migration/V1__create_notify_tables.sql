-- One row per (page event, contact). The unique key makes page handling idempotent: a redelivered
-- PageRequested finds its rows and only sends the ones that are still pending.
CREATE TABLE delivery (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    uuid        NOT NULL,
    incident_id uuid        NOT NULL,
    person_id   text        NOT NULL,
    channel     text        NOT NULL CHECK (channel IN ('TELEGRAM', 'EMAIL')),
    address     text        NOT NULL,
    step        integer     NOT NULL,
    status      text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'RATE_LIMITED')),
    attempts    integer     NOT NULL DEFAULT 0,
    last_error  text,
    claimed_at  timestamptz,
    created_at  timestamptz NOT NULL,
    sent_at     timestamptz,
    UNIQUE (event_id, channel, address)
);

CREATE INDEX delivery_recent ON delivery (incident_id, person_id, channel, created_at DESC);

-- Each email action link works once.
CREATE TABLE action_token_use (
    nonce   text        PRIMARY KEY,
    used_at timestamptz NOT NULL
);
