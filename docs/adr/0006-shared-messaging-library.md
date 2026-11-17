# 6. Shared messaging library, database timers and in-house retries

- Status: accepted
- Date: 2026-11-18
- Amends: [0002](0002-java-spring-boot-microservices.md) (what may be shared in `libs/`)

## Context

ADR 0002 allowed only event definitions and security settings in `libs/`. With the `schedule` service,
a second service needs the transactional outbox from ADR 0005: the relay, the confirmed publisher, and
the processed-event check. Copying that code means two copies of the most delicate part of the system
drifting apart.

Day 3 also needs escalation timers that survive restarts, and retries for Telegram and email. The plan
named `db-scheduler` and Resilience4j for these.

## Decision

- Add `libs/messaging`: the outbox repository, relay, confirmed publisher and processed-event
  repository, wired by a Spring Boot auto-configuration. A service that publishes events adds the
  dependency and the two tables to its own migration. Services that only consume (`notify`, `status`)
  do not use it.
- Escalation timers are rows in `escalation_job` with a `fire_at` time, picked up every second with
  `SELECT ... FOR UPDATE SKIP LOCKED`. Firing a step and writing its `PageRequested` to the outbox happen
  in one transaction.
- Retries in `notify` use a small class (about 60 lines): exponential backoff with full jitter, a
  maximum number of attempts, no retry for 4xx answers, and Telegram's `retry_after` respected.

## Alternatives considered

- **Copy the outbox code into each service:** keeps services fully independent, but bug fixes must be
  made several times, and the copies would drift.
- **`db-scheduler`:** a good library with the same database-backed idea, but escalation jobs need
  domain columns (step, round, target, who was paged) and must be cancelled by incident. Plain tables
  are easier to query, test and explain, and reuse the same `SKIP LOCKED` pattern as the outbox.
- **Resilience4j:** full-featured (circuit breakers, bulkheads), but at the time of writing its Spring
  Boot integration lagged behind Spring Boot 4, and only retry with backoff is needed now.

## Consequences

- One place to fix and test the outbox; `incident` and `schedule` share it. A change to the library
  affects both, so it has its own unit tests and both services' integration tests cover it.
- Timers survive restarts and can run on several replicas without double paging. They fire within
  about one second of their time.
- If circuit breakers or rate limiting are needed later, Resilience4j can replace the retry class
  without changing callers.
