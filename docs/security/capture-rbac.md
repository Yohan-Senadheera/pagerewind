# What `capture` may read, and why

`capture` records recent container logs (the flight recorder) and changes in the cluster ("what
changed"). It only reads. It cannot change, create or delete anything, cannot run commands in pods
(`exec`), and cannot read Secrets.

## Opt-in per namespace

Logs and config map changes are recorded only in namespaces labelled:

```
pagerewind.dev/record=true
```

Without the label, nothing from that namespace's logs is ever read or kept.

## Permissions (ClusterRole `pagerewind-capture`, verbs `get`, `list`, `watch` only)

| Resource | Why | Notes |
|---|---|---|
| `namespaces` | Find which namespaces opted in | Labels only are used |
| `pods` | Know which containers run and when they restart or are deleted | |
| `pods/log` | Follow container logs into the in-memory buffer | Only for opted-in namespaces (enforced in code) |
| `events` | Planned: pod events in the evidence bundle | |
| `deployments`, `replicasets` (apps) | Rollouts, image changes, scaling | Cluster-wide metadata and pod templates |
| `configmaps` | That a config map changed, and which keys | Only in opted-in namespaces. **Values are never stored.** Kubernetes RBAC cannot grant metadata only, so the code is the boundary here; see below. |
| `horizontalpodautoscalers` (autoscaling) | Scaling decisions | |
| `nodes` | Node Ready/NotReady changes | |
| `applications` (argoproj.io) | Argo CD syncs and their revision | Optional; skipped if Argo CD is not installed |

Not granted: `secrets`, `pods/exec`, `pods/attach`, `pods/portforward`, and every write verb.

## Known limits

- **Config maps:** reading a config map returns its data. `capture` records only the names of changed
  keys, never values, but anyone who controls the `capture` pod could read config map data cluster-wide.
  Keep secrets in Secrets (which `capture` cannot read), not in config maps.
- **Logs can contain secrets.** Every line is redacted before it is buffered (Authorization headers,
  JWTs, `password=`/`secret=`/`token=`/API key values, credentials in URLs, plus configurable patterns).
  Redaction is pattern-based and cannot catch everything; do not log secrets.
- **The buffer is in memory.** After a `capture` restart the window starts empty (ADR planned: why not
  persist every log line).
