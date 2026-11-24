# Runbook: PageRewind database unreachable

All services keep their data in one PostgreSQL (`postgres-0` in namespace `pagerewind`, schema per
service). Every service writes its outgoing events to an outbox table in the same transaction as its
data, so while the database is unreachable nothing new is accepted, but nothing already accepted is lost.

## Symptoms

- Alert `AlertIntakeBudgetBurnFast` (alert webhooks get 5xx) and, if pages were due,
  `PageDeliveryBudgetBurnFast`. PageRewind sends these to itself, so they arrive **after** the database
  is back (see "Known gap").
- Alert senders such as Alertmanager get HTTP 5xx from `ingest` and retry on their own.
- Service readiness may drop; the web UI and status page return errors.
- Logs: `Connection to postgres:5432 refused` or `HikariPool ... Connection is not available`.

## Check

```bash
kubectl -n pagerewind get pod postgres-0                    # Running and 1/1?
kubectl -n pagerewind exec postgres-0 -- pg_isready -U pagerewind
kubectl -n pagerewind get networkpolicy                     # anything blocking 5432?
kubectl -n pagerewind logs deploy/ingest --since=10m | grep -i -m5 "postgres\|hikari\|connection"
kubectl -n pagerewind describe pod postgres-0 | tail -20    # OOMKilled? disk full? node gone?
```

## Fix

| Cause | Action |
|---|---|
| Pod crashed or OOMKilled | It restarts on its own; if it loops, read `kubectl logs postgres-0 --previous` |
| Disk full | `kubectl -n pagerewind exec postgres-0 -- df -h /var/lib/postgresql/data`; grow the volume or delete old data |
| Node down | The volume is local to its node: the pod can only return with that node (restore from backup otherwise, below) |
| Blocked by a NetworkPolicy | Remove the policy |
| Data lost or corrupted | Restore the newest backup: `docs/runbooks/` restore steps, `platform/backups/restore-test.yaml` shows the commands |

## After it is back

- Services reconnect on their own (connection pool retries); no restart needed.
- Outboxes publish what was accepted before the outage; alert senders resend what they could not deliver.
- Check: `kubectl -n pagerewind logs deploy/incident --since=5m | grep -c "Incident opened"`; the
  delayed self-alert incident should be open with the evidence of the failing service attached.

## Known gap

PageRewind cannot page anyone about its own database while the database is down: its self-alerts go
through its own ingest. The Watchdog (healthchecks.io) only fires if Alertmanager itself stops. To be
paged *during* such an outage, critical self-alerts need a second route that does not depend on
PageRewind (for example Alertmanager's own Telegram or email receiver).
