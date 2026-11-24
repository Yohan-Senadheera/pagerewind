# Runbook: a node is down

The reference cluster has two nodes: `server` (control plane, PostgreSQL, most PageRewind services) and
`agent` (RabbitMQ, Keycloak and its database, Prometheus and Alertmanager, Traefik, CoreDNS, Kyverno,
Argo CD's other parts). Volumes are local to a node (k3s local-path), so a pod with a volume can only
run on the node that holds its data.

## What to expect when `agent` is down

| Time | What happens |
|---|---|
| 0 to 1 min | Nothing visible yet; the node stops answering |
| ~1 min | Node `NotReady`; traffic through Traefik and DNS lookups start failing |
| ~5 min | Pods on the node are marked for eviction and recreated on `server` where they fit |
| ongoing | Pods with volumes on `agent` (RabbitMQ, Keycloak's database, Prometheus) cannot move: they wait |
| ongoing | Kyverno's admission webhook fails closed: new pods in `pagerewind`, `keycloak` and `whoami` are refused until Kyverno runs again |
| 10 to 11 min | healthchecks.io stops receiving the Watchdog (Alertmanager is down) and emails the owner |

PageRewind cannot page about this: RabbitMQ and Alertmanager are on the failed node. The healthchecks.io
Watchdog is the alarm for this case.

## Check

```bash
kubectl get nodes
aws ec2 describe-instance-status --profile pager --include-all-instances \
  --query "InstanceStatuses[].[InstanceId,InstanceState.Name,SystemStatus.Status,InstanceStatus.Status]"
kubectl get pods -A --field-selector=status.phase=Pending
kubectl get pods -A -o wide | grep agent
```

## Fix

- Instance stopped: `aws ec2 start-instances --profile pager --instance-ids <agent id>`, or
  `scripts/pagerewind.ps1 start`.
- Instance impaired: stop and start it (it moves to other hardware; the EBS root volume and its local-path
  data stay).
- Instance and disk lost: `scripts/pagerewind.ps1 down`/`up` rebuilds; RabbitMQ queues and Prometheus
  history are lost, Keycloak's database is rebuilt by CloudNativePG from scratch, the PageRewind database
  (on `server`) is untouched, and backups cover the rest.

After the node is back, pods return on their own within a few minutes. Then re-enrol the mesh if pods
started before Istio was ready (`scripts/pagerewind.ps1 start` does this) and check:

```bash
kubectl get pods -A | grep -vE "Running|Completed"
curl -s https://status.pagerewind.dev/v1/status | jq .overall
```

## Lessons to act on

- Single points of failure: one RabbitMQ, one Keycloak database, one Prometheus, all on one node.
- Fail-closed admission webhooks block recovery when the webhook runs on the failed node.
- Local volumes keep stateful pods on their node; replicated storage or replicas on both nodes would let
  them move.
