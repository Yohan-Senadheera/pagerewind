# Install

## Helm (Kubernetes)

Everything bundled (PostgreSQL, RabbitMQ, Redis, Mailpit and a development Keycloak), so it works on an
empty cluster. Needs Helm 3.8 or newer and about 5 GB of memory.

```bash
helm install pagerewind oci://ghcr.io/yohan-senadheera/charts/pagerewind --version 0.2.0 \
  -n pagerewind --create-namespace
```

The install prints how to create a login and send a test alert. A full walk-through on kind (ingress,
hostnames that need no DNS setup), production values with your own data stores and Keycloak, the list of
secret keys and every setting are in the
[chart README](https://github.com/Yohan-Senadheera/pagerewind/blob/main/charts/pagerewind/README.md).

## Docker Compose

No flight recorder (it needs Kubernetes). Needs Docker with Compose and about 4 GB of free memory.

```bash
git clone https://github.com/Yohan-Senadheera/pagerewind.git && cd pagerewind
cp .env.example .env            # then replace every "change-me" (openssl rand -hex 24)
docker compose up -d
./scripts/seed-demo.sh          # logins alice, bob, vera; Alice is on call, Bob is paged after 1 minute
./scripts/send-test-alert.sh    # send it several times: still one incident
```

| What | Where |
|---|---|
| Incidents and who is on call (`alice`/`bob` responders, `vera` viewer; password `DEMO_PASSWORD` from `.env`) | http://localhost:8080 |
| Pages sent by email | http://localhost:8025 |
| Public status page | http://localhost:8085 |
| Keycloak admin console | http://localhost:8180 |

## Verify the images

Every image is signed by this repository's CI and carries a signed SBOM:

```bash
cosign verify ghcr.io/yohan-senadheera/pagerewind-incident:0.2.0 \
  --certificate-identity-regexp '^https://github\.com/Yohan-Senadheera/pagerewind/\.github/workflows/images\.yml@refs/(heads/main|tags/v[0-9.]+)$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

## Connect Prometheus Alertmanager

Add a webhook receiver for `http://pagerewind-ingest.pagerewind.svc/v1/alerts/alertmanager` with the ingest
token as a bearer token:

```bash
kubectl -n pagerewind get secret pagerewind-secrets -o jsonpath='{.data.ingest-token}' | base64 -d
```
