# 8. GitOps with an Argo CD app-of-apps, and a rebuildable cluster

- Status: accepted
- Date: 2026-11-18

## Context

Everything that runs in the cluster (cert-manager, issuers, later Keycloak, monitoring and PageRewind
itself) must be reproducible from Git. CI has no AWS or cluster credentials (ADR 0007), so deployments
cannot be pushed from CI. The cluster should also be cheap to throw away and rebuild, which only works
if a rebuild needs no manual clicks.

## Decision

- **Argo CD** runs in the cluster and pulls from the `main` branch.
- **App-of-apps:** one `root` Application watches `platform/apps/`. Each file there is an Argo CD
  Application for one add-on or service. Adding a component means adding a file in a pull request.
- All Applications sync automatically with `prune` and `selfHeal`, so the cluster always matches Git
  and manual changes are reverted.
- Helm charts are pinned to exact versions in their Application. Ordering problems (a custom resource
  whose type is installed by another app) are handled with `SkipDryRunOnMissingResource` and retries.
- **Two manual steps only:** installing Argo CD itself with Helm (pinned version,
  `platform/argocd-values.yaml`) and applying `platform/root-app.yaml`. `scripts/pagerewind.ps1 up` does
  both after Terraform, so a full rebuild is one command.
- The Elastic IP lives in its own Terraform stack (`infra/ip`) so a rebuild keeps the same address and
  DNS stays valid.

## Alternatives considered

- **Flux:** also pull-based and lighter, but Argo CD's UI makes the state of every component visible,
  which helps when demonstrating and debugging.
- **ApplicationSet with a Git directory generator instead of app-of-apps:** less YAML per component, but
  each component needs its own settings (chart, version, namespace, sync options), so explicit
  Application files are clearer at this size. ApplicationSet is still used for preview environments.
- **`helm install` from CI or from the laptop:** simple, but drifts from Git and needs cluster
  credentials outside the cluster.

## Consequences

- A change reaches the cluster only through a merged pull request; Git history is the deployment log.
- Argo CD notices changes within about 3 minutes (or immediately with a refresh).
- Argo CD itself is upgraded by changing the pinned version in the script and running `up` again.
- After a rebuild, the Argo CD admin password is new and must be changed again.
