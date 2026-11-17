-- Read model built from incident events. Only what the public page needs: no summaries or labels.
CREATE TABLE status_incident (
    incident_id uuid        PRIMARY KEY,
    service     text,
    severity    text,
    status      text        NOT NULL CHECK (status IN ('TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED')),
    opened_at   timestamptz,
    resolved_at timestamptz,
    updated_at  timestamptz NOT NULL
);

CREATE INDEX status_incident_open ON status_incident (service) WHERE status <> 'RESOLVED';

CREATE TABLE processed_event (
    event_id     uuid        PRIMARY KEY,
    processed_at timestamptz NOT NULL
);
