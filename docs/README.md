# PageRewind documentation

| Topic | Where |
|---|---|
| Architecture (product and reference deployment) | [architecture.md](architecture.md) |
| Design decisions | [adr/](adr/) |
| Install with Helm (kind quickstart, production values, verifying images) | [../charts/pagerewind/README.md](../charts/pagerewind/README.md) |
| Security: what the flight recorder may read | [security/capture-rbac.md](security/capture-rbac.md) |

## Runbooks

| Situation | Runbook |
|---|---|
| The database is unreachable; replaying dead letters | [runbooks/database-down.md](runbooks/database-down.md) |
| A node is down | [runbooks/node-failure.md](runbooks/node-failure.md) |
| Keycloak (logins) is down | [runbooks/keycloak-down.md](runbooks/keycloak-down.md) |
| Releasing with a canary; rehearsing a bad release | [runbooks/canary-release.md](runbooks/canary-release.md) |

## Failure drills (post-mortems)

| Date | Drill | Main finding |
|---|---|---|
| 24 Nov 2026 | [Database unreachable for 5 minutes](postmortems/2026-11-24-drill-database-unreachable.md) | Intake stayed up and nothing was lost, but nobody was paged and 3 alerts waited in a dead-letter queue; now alerted and routed around PageRewind |
| 24 Nov 2026 | [Agent node fails for 15 minutes](postmortems/2026-11-24-drill-node-failure.md) | 5 minutes of total public outage; single-instance stateful parts on one node; self-recovery in 1 minute |
| 24 Nov 2026 | [Keycloak unavailable](postmortems/2026-11-24-drill-keycloak-down.md) | Paging and signed-in API use do not depend on Keycloak; a deny-all NetworkPolicy cannot override another policy's allow |
