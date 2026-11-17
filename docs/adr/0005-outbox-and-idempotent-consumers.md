# 5. Transactional outbox and idempotent consumers

- Status: accepted
- Date: 2026-11-18

## Context

The services talk through RabbitMQ. Two failures must never lose or double an incident:

1. **Dual write.** The `incident` service changes its database and then publishes an event. If the
   process dies between the two, either the event is lost (database committed, nothing published) or
   it describes a change that never happened (published, database rolled back).
2. **Redelivery.** RabbitMQ delivers at least once. After a crash or a lost acknowledgement, the same
   message arrives again, and the same alert can also be sent many times by Alertmanager.

On top of that, many copies of the same alert can arrive at the same moment, and they must end up as
one incident.

## Decision

- **Transactional outbox.** Every state change writes its event to an `outbox` table in the same
  database transaction. A relay reads unpublished rows with
  `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 50`, publishes each with publisher confirms, and sets
  `published_at`. Rows are published in order and a batch stops at the first failure.
- **Idempotent consumers.** A consumer inserts the event's `eventId` into `processed_event` in the
  same transaction as its work. If the insert finds the id already there, the event was handled
  before, and the message is acknowledged without doing anything.
- **One open incident per fingerprint** is enforced by the database:
  `CREATE UNIQUE INDEX ... ON incident (fingerprint) WHERE status <> 'RESOLVED'`, with
  `INSERT ... ON CONFLICT DO NOTHING` and then a locked read of the open incident.
  The Redis filter in `ingest` only marks repeats to save work; correctness does not depend on it.
- **Poison messages.** Consumer queues are quorum queues with a delivery limit. A message that fails
  5 times is moved to a dead-letter queue instead of blocking the queue.
- **Ingest** answers `202` only after RabbitMQ confirms every message, and `503` with `Retry-After`
  otherwise, so Alertmanager retries instead of the alert being lost.

## Alternatives considered

- **Publish directly after commit:** simple, but loses events when the process dies between commit
  and publish.
- **Distributed transactions (XA) across PostgreSQL and RabbitMQ:** RabbitMQ does not support XA, and
  XA is slow and hard to operate.
- **Change data capture (Debezium) instead of a polling relay:** no polling delay and no extra queries,
  but needs Kafka Connect or Debezium Server and logical replication; too heavy for this project.
- **Exactly-once delivery:** not offered by RabbitMQ; at-least-once plus idempotency gives the same
  result for the user.

## Consequences

- Events are delivered at least once and may be delivered twice (for example after a crash between a
  broker confirm and the `published_at` commit). Every consumer must use `processed_event`.
- Events reach consumers up to one poll interval (500 ms) after the change.
- `outbox` and `processed_event` grow; a cleanup job for old rows is needed later.
- Several `incident` replicas can run the relay at the same time; `SKIP LOCKED` keeps them from
  publishing the same row.
- The guarantees are covered by integration tests against real PostgreSQL and RabbitMQ: 100 copies
  of one alert make one incident, redelivered events count once, a publisher that dies mid-batch
  loses nothing, two relays never publish the same row, and a poison message ends in the
  dead-letter queue after 5 attempts.
