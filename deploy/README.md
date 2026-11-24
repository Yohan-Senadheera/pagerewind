# Deploy

| Folder | What it is |
|---|---|
| `pagerewind/` | PageRewind on the reference cluster (Kustomize), applied by the Argo CD application `pagerewind` |
| `keycloak/` | The `pagerewind` Keycloak realm as code, used by the Compose quickstart, the Helm chart and the cluster |

To install PageRewind on your own cluster, use the Helm chart in [../charts/pagerewind](../charts/pagerewind)
instead; these manifests are specific to the reference deployment (its domain and its AWS secrets).

## How a new version gets here

You never edit image versions by hand. After a merge to `main`, the Container images workflow builds,
scans and signs the images, then opens a pull request "Deploy sha-..." that changes
`pagerewind/kustomization.yaml` to `sha-<commit>@sha256:<digest>` for every service
(`scripts/pin-images.sh`). Merging that pull request is the deploy approval. `status` then goes out as a
canary (`pagerewind/status.yaml`, Argo Rollouts).

Secrets are not here: `pagerewind/external-secret.yaml` copies them from AWS Secrets Manager.
