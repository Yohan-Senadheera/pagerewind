# Runbook: canary releases (status service)

The `status` service is released as a canary by Argo Rollouts (`deploy/pagerewind/status.yaml`):

1. A new version gets one of the two pods (50%).
2. After 30 seconds, Prometheus measures the error rate of that pod's requests every 30 seconds for
   2 minutes (`platform/rollouts/analysis-templates.yaml`, ClusterAnalysisTemplate `http-error-rate`).
3. All four measurements at or below 5%: the new version takes both pods. Any measurement above 5%: the
   rollout aborts, the canary pod is removed after 30 seconds and the old version serves everything.

Nobody has to watch it. A version that never becomes ready aborts after 10 minutes
(`progressDeadlineAbort`).

## Watch a rollout

```bash
kubectl -n pagerewind get rollout status -w
kubectl -n pagerewind get analysisrun
kubectl -n pagerewind describe analysisrun <name>     # each measurement and its value
```

The status page gets little traffic, so measurements can be empty (counted as 0% errors). For a test,
send traffic while the canary runs:

```bash
kubectl -n pagerewind run load --rm -i --restart=Never --image=curlimages/curl:8.17.0 \
  --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":100}}}' -- \
  sh -c 'for i in $(seq 1 1500); do curl -s -o /dev/null http://status/v1/status; sleep 0.2; done'
```

## Rehearse a bad release

`pagerewind.status.fault-percent` (env `PAGEREWIND_STATUS_FAULT_PERCENT`) makes that share of status
page requests fail with 503 while health checks stay green, like a real bad release. Set it to 50 in
`deploy/pagerewind/status.yaml`, merge, send traffic, and watch the analysis fail and the rollout abort.
Then revert the change in Git: the aborted rollout stays on the old version until Git describes a good one.

## After an abort

- `kubectl -n pagerewind get rollout status` shows `Degraded` with the old version serving: users are fine.
- Find the cause in the AnalysisRun (which measurement failed) and the canary's logs.
- Fix forward or revert in Git; Argo CD syncs and a new canary starts.
- Manual escape hatches: `kubectl argo rollouts promote status -n pagerewind` (skip the analysis) or
  `kubectl argo rollouts abort status -n pagerewind`.
