# Post-mortem: drill 1, PageRewind database unreachable for 5 minutes

Date: 24 Nov 2026. Type: planned failure drill on the reference cluster. Blameless.

## Summary

PostgreSQL was made unreachable from all PageRewind services for 5 minutes (21:34 to 21:39) while one
test alert was sent every 15 seconds. Alert intake kept working, processing stopped and caught up within
a minute of recovery, and no alert was lost in the end. But **nothing paged anyone during the outage**,
and **3 alerts were parked in a dead-letter queue** and only processed after a manual replay.

## Timeline (Asia/Colombo)

| Time | Event |
|---|---|
| 21:33:36 | Test alerts start (one fingerprint, every 15 s) |
| 21:33:54 | Istio AuthorizationPolicy denies all connections to `postgres-0`; 45 open connections closed |
| 21:34 to 21:39 | incident, schedule, notify, capture log connection errors; ingest keeps answering 202 |
| 21:34 to 21:39 | No incident events written; RabbitMQ holds the alerts; 3 messages use up their 5 delivery attempts (delivery limit 4) and go to `incident.alerts.dlq` |
| 21:39:01 | Policy removed |
| 21:39 | 20 queued alerts processed within the minute |
| 21:41 | Check: incident shows 26 of 29 accepted alerts; no alert fired except generic warnings (TargetDown, KubeMemoryOvercommit, routed nowhere) |
| 21:44 | 3 dead letters replayed with a RabbitMQ shovel; incident shows 29 of 29 |

## What went well

- Ingest does not need the database to accept an alert: senders saw no errors (29 of 29 accepted).
- Queues absorbed the outage; recovery needed no restarts.
- The outbox pattern and idempotent consumers meant replayed and retried messages were counted once.

## What went wrong

1. **No page during the outage.** Both SLOs measure the front door (delivered pages, accepted webhooks);
   the front door stayed healthy while work behind it stopped. The services already counted failed
   attempts, but no alert watched those counters.
2. **Even with such an alert, PageRewind could not have delivered it**: its own alerts go through its own
   pipeline, which was the broken part.
3. **Retries were used up in seconds.** Five immediate deliveries do not span a database blip of a few
   minutes, so messages were dead-lettered, and nothing watched the dead-letter queues.

## Things learned while setting the drill up

- A Kubernetes NetworkPolicy did not cause an outage: the services' 45 pooled connections stayed open
  (policies judge new connections only).
- Even new connections got through: in the Istio ambient mesh, pod traffic rides long-lived ztunnel
  tunnels between nodes, which the NetworkPolicy did not cut. **Network isolation between meshed pods has
  to use Istio AuthorizationPolicies**, which ztunnel checks on every connection.

## Actions

| Action | Status |
|---|---|
| Alert `PageRewindProcessingFailing` on the services' failure counters, `PageRewindServiceDown` on scrapes | Done (`platform/monitoring/pipeline-rules.yaml`) |
| Critical alerts about PageRewind also go to healthchecks.io `/fail`, which emails at once without PageRewind | Done (`platform/apps/monitoring.yaml`, route `healthchecks-fail`) |
| Replay procedure for dead letters | Done (`docs/runbooks/database-down.md`) |
| Retries with back-off over minutes before dead-lettering | Open |
| Alert on dead-letter queue depth (needs RabbitMQ metrics) | Open |
