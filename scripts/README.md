# Scripts

## Quickstart and tests

| Script | What it does |
|---|---|
| `send-test-alert.sh [firing\|resolved] [fingerprint]` | Sends an Alertmanager-style alert to ingest (needs `INGEST_TOKEN`) |
| `seed-demo.sh` | Creates the demo logins, team, rotation and escalation policy in the Compose quickstart |
| `e2e.sh` | The end-to-end test of the Compose quickstart (run by CI) |
| `chart-smoke.sh [release] [namespace]` | Checks a fresh Helm install: alert to status page, Keycloak issuer, API with and without a token, login redirect (run by CI on kind) |
| `render-realm-import.py` | Builds the cluster's Keycloak realm import from `deploy/keycloak/` (CI checks it is up to date) |

## Reference deployment

| Script | What it does |
|---|---|
| `pagerewind.ps1 up \| down \| start \| stop \| extend \| status \| kubeconfig \| allow-me` | Build, tear down, start and stop the AWS cluster; `start` schedules an automatic stop (`-Hours`) and updates the firewall to your current IP |
| `pin-images.sh <tag>` | Points `deploy/pagerewind` at one build, pinned by digest (used by the deploy pull request) |
| `apply-node-config.sh <server IP> <agent IP>` | Applies the k3s memory reservation to running nodes |
| `demo/demo.ps1` | Live demo helpers: `bad-deploy`, `fix-deploy`, Telegram webhook, demo team |
| `demo/new-secrets.ps1`, `demo/keycloak-secrets.ps1`, `demo/push-secrets-to-aws.ps1` | Generate secrets and store them in AWS Secrets Manager (never in Git) |
| `demo/keycloak-users.ps1` | Creates logins in Keycloak |

The scripts never print secrets.
