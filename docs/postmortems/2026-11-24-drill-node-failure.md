# Post-mortem: drill 2, the agent node fails for 15 minutes

Date: 24 Nov 2026. Type: planned failure drill on the reference cluster. Blameless.

## Summary

The `agent` EC2 instance was stopped for 15 minutes (21:43 to 21:58) and then started again. The public
site was unreachable for about 5 minutes until Kubernetes moved the ingress to the other node; 36 pods
stayed down for the rest of the outage. After the node came back, every pod was running within about a
minute and a real alert was paged on Telegram and email. During the outage PageRewind could not take
alerts at all, and it could not tell anyone, because its message broker and Alertmanager were on the
failed node; only the external Watchdog (healthchecks.io) could notice.

## Timeline (Asia/Colombo)

| Time | Event |
|---|---|
| 21:43:10 | `agent` instance stopped (`aws ec2 stop-instances`) |
| 21:44:36 | Node `NotReady`; status page and ingest give no answer at all (Traefik ran on `agent`) |
| 21:44 to 21:48 | Pods on `agent` still counted as running: Kubernetes waits 5 minutes before evicting them |
| 21:49:44 | Pods evicted; Traefik and the services without volumes start on `server`; the public site answers again; 36 pods not running |
| 21:49 to 21:58 | Pods with volumes on `agent` (RabbitMQ, Keycloak's database, Prometheus) cannot move and wait |
| 21:58:20 | `agent` started |
| 21:59:27 | Node `Ready`; all pods running |
| 22:12 | Real test alert: incident opened, Telegram and email pages sent |

## What went well

- Self-recovery: no manual step was needed after the node returned.
- Stateless pods (ingress, PageRewind services) moved to the healthy node on their own.
- The PageRewind database lives on `server` and was never affected.

## What went wrong

1. **Five minutes of total public outage** while Kubernetes waited to evict pods (the default
   `tolerationSeconds: 300` for unreachable nodes) and the only ingress controller was on the failed node.
2. **Single points of failure on one node:** RabbitMQ, Keycloak's database and Prometheus/Alertmanager
   all have one instance with a node-local volume, so they could not move.
3. **No alarm from inside the cluster**: Alertmanager was on the failed node. Only the healthchecks.io
   Watchdog (pings stopped at 21:43) could notice.

## Actions

| Action | Status |
|---|---|
| Watchdog to healthchecks.io as the outside alarm for this case | In place (period 5 min + grace 10 min) |
| Ingress and CoreDNS with two replicas spread over both nodes, or shorter eviction wait for them | Open |
| Spread single-instance stateful parts over both nodes (or accept and document the blast radius) | Open; accepted for a two-node demo cluster |
| Replicated storage or a second RabbitMQ / database replica | Open; needs more than two small nodes |
