CREATE TABLE person (
    id   text PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    name text NOT NULL
);

CREATE TABLE contact (
    person_id text NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    channel   text NOT NULL CHECK (channel IN ('TELEGRAM', 'EMAIL')),
    address   text NOT NULL,
    PRIMARY KEY (person_id, channel, address)
);

CREATE TABLE team (
    id            text    PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    name          text    NOT NULL,
    policy_repeat integer NOT NULL DEFAULT 0 CHECK (policy_repeat BETWEEN 0 AND 10),
    is_default    boolean NOT NULL DEFAULT false
);

-- At most one team receives incidents for services that no team claims.
CREATE UNIQUE INDEX team_one_default ON team (is_default) WHERE is_default;

CREATE TABLE team_service (
    service text PRIMARY KEY,
    team_id text NOT NULL REFERENCES team (id) ON DELETE CASCADE
);

CREATE TABLE rotation (
    id          text    PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    team_id     text    NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    name        text    NOT NULL,
    start_local timestamp NOT NULL,
    time_zone   text    NOT NULL,
    shift_hours integer NOT NULL CHECK (shift_hours BETWEEN 1 AND 672)
);

CREATE TABLE rotation_member (
    rotation_id text    NOT NULL REFERENCES rotation (id) ON DELETE CASCADE,
    position    integer NOT NULL,
    person_id   text    NOT NULL REFERENCES person (id),
    PRIMARY KEY (rotation_id, position)
);

CREATE TABLE rotation_override (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rotation_id text        NOT NULL REFERENCES rotation (id) ON DELETE CASCADE,
    person_id   text        NOT NULL REFERENCES person (id),
    start_at    timestamptz NOT NULL,
    end_at      timestamptz NOT NULL,
    CHECK (end_at > start_at)
);

CREATE INDEX rotation_override_lookup ON rotation_override (rotation_id, end_at);

CREATE TABLE escalation_step (
    team_id                text    NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    step_no                integer NOT NULL CHECK (step_no >= 1),
    escalate_after_minutes integer NOT NULL CHECK (escalate_after_minutes BETWEEN 1 AND 1440),
    target_type            text    NOT NULL CHECK (target_type IN ('ROTATION', 'PERSON')),
    target_id              text    NOT NULL,
    PRIMARY KEY (team_id, step_no)
);

-- One row per incident this service has heard about. Details are empty when an acknowledge or
-- resolve event arrived before the trigger event.
CREATE TABLE incident_escalation (
    incident_id uuid        PRIMARY KEY,
    team_id     text,
    service     text,
    severity    text,
    summary     text,
    status      text        NOT NULL CHECK (status IN ('ACTIVE', 'STOPPED')),
    stop_reason text,
    created_at  timestamptz NOT NULL,
    stopped_at  timestamptz
);

-- Escalation timers live in the database, so they survive restarts and several replicas can
-- share the work with SKIP LOCKED. The target is copied in, so editing a policy does not change
-- escalations that are already running.
CREATE TABLE escalation_job (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id uuid        NOT NULL REFERENCES incident_escalation (incident_id) ON DELETE CASCADE,
    step_no     integer     NOT NULL,
    round       integer     NOT NULL,
    target_type text        NOT NULL,
    target_id   text        NOT NULL,
    fire_at     timestamptz NOT NULL,
    status      text        NOT NULL CHECK (status IN ('PENDING', 'FIRED', 'SKIPPED', 'CANCELLED')),
    fired_at    timestamptz,
    person_id   text,
    note        text,
    UNIQUE (incident_id, step_no, round)
);

CREATE INDEX escalation_job_due ON escalation_job (fire_at) WHERE status = 'PENDING';

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
