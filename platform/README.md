# Platform (Argo CD, GitOps)

Everything that runs on the reference cluster besides PageRewind itself. Argo CD keeps the cluster equal
to this folder: change it in a pull request, and after the merge Argo CD applies it.

`root-app.yaml` is the only thing applied by hand (by `scripts/pagerewind.ps1 up`, together with Argo CD
from `argocd-values.yaml`). It watches `apps/`: **each file in `apps/` is one Argo CD application** (ADR 0008).

| Application (`apps/`) | What it runs | Settings |
|---|---|---|
| `cert-manager`, `cluster-issuers` | HTTPS certificates from Let's Encrypt | `cluster-issuers/` |
| `cloudnative-pg` | PostgreSQL operator (Keycloak's database) | |
| `keycloak` | Keycloak operator, database and server; realm imported from the repo | `keycloak/` |
| `external-secrets` | Copies secrets from AWS Secrets Manager into Kubernetes | `external-secrets/` |
| `kyverno`, `policies` | Admission policies: non-root, memory limits, probes, no host namespaces, signed images only | `policies/` |
| `istio` | Ambient mesh: mutual TLS between PageRewind's pods (ADR 0010) | |
| `pagerewind` | PageRewind itself | `../deploy/pagerewind/` |
| `monitoring`, `monitoring-config` | Prometheus, Alertmanager, Grafana; SLO and processing alerts | `monitoring/` |
| `argo-rollouts`, `rollouts-config` | Canary releases with automatic rollback | `rollouts/` |
| `backups` | Database backup to S3 every 6 hours and a weekly restore test | `backups/` |
| `demo` | A small app for flight-recorder demos | `demo/` |
| `whoami` | A test page for ingress and HTTPS | `whoami/` |

Runbooks for operating this: [../docs/runbooks/](../docs/runbooks/).
