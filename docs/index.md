# PageRewind

**On-call that pages you, then rewinds what your pods did before they died.**

PageRewind is an open-source, self-hosted on-call and incident tool for Kubernetes teams. An alert comes
in, duplicates become one incident, the person on call is paged on Telegram or email, and if nobody
acknowledges, the page escalates. Its **flight recorder** attaches the last logs of the failing workload,
even of pods that already crashed and were deleted, and the recent changes, with the most likely cause.

[Install](install.md){ .md-button .md-button--primary }
[Source on GitHub](https://github.com/Yohan-Senadheera/pagerewind){ .md-button }

## What it does

| Area | What it does |
|---|---|
| Alert intake | Alertmanager and generic webhooks, token or HMAC signature, `202` only once stored |
| Incidents | One incident per problem (database-enforced), acknowledge and resolve, timeline, repeat occurrences |
| On-call | Teams, rotations, overrides, escalation policies; timers survive restarts |
| Paging | Telegram with buttons; email with signed single-use links |
| Flight recorder | Recent logs (masked, memory-capped), deleted pods' last lines, recorded changes, evidence bundle and likely cause |
| Status page | Public, per service, last 14 days of incidents |
| Logins | Keycloak sign-in through a gateway; viewer, responder and admin roles |

## Where to go next

- **[Install](install.md)** with Helm (everything bundled, works on kind) or Docker Compose.
- **[Architecture](architecture.md)**: services, events, the flight recorder and the reference deployment.
- **Runbooks** for operating it, starting with [the database is unreachable](runbooks/database-down.md).
- **Failure drills**: what happened when the database, a node and Keycloak failed on the reference cluster,
  starting with [the database drill](postmortems/2026-11-24-drill-database-unreachable.md).
- **Decisions**: why each choice was made, starting with [the outbox pattern](adr/0005-outbox-and-idempotent-consumers.md).
