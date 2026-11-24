# Services

Seven Spring Boot services (Java 21). Each has its own PostgreSQL schema (Flyway migrations in
`src/main/resources/db/migration`), talks to the others through RabbitMQ events (transactional outbox,
idempotent consumers, dead-letter queues), exposes health probes and Prometheus metrics, and logs JSON.
Shared code is in [../libs](../libs): `events` (event types), `messaging` (outbox), `security` (Keycloak tokens
and roles).

| Service | Job | Port (local) |
|---|---|---|
| `gateway` | Signs people in with Keycloak (PKCE), forwards their token, CSRF checks, rate limits | 8080 |
| `ingest` | Receives alerts (Alertmanager and generic webhooks), checks the token or signature, publishes | 8081 |
| `incident` | Incident life cycle, timeline, evidence, REST API and web UI | 8082 |
| `schedule` | People, teams, rotations, escalation policies, durable escalation timers | 8083 |
| `notify` | Telegram and email pages; acknowledge and resolve from the page | 8084 |
| `status` | Public status page with the last 14 days of incidents | 8085 |
| `capture` | Flight recorder and "what changed": recent logs, recorded changes, evidence and likely cause | 8086 |

Build and test one service: `./mvnw -pl services/<name> verify` (integration tests start real dependencies
with Testcontainers; Docker must be running). How they fit together: [../docs/architecture.md](../docs/architecture.md).
