# PageRewind Helm chart

Installs PageRewind: alerts in, incidents out, the on-call person paged by Telegram or email, a public
status page, and a flight recorder that attaches the last logs and recent changes to each incident.

With the default values everything runs inside the release (PostgreSQL, RabbitMQ, Redis, Mailpit and a
development-mode Keycloak), which is right for trying it out. For production, switch the bundled parts
off and point the chart at your own (see [Production](#production)).

Every pod runs as non-root with a memory limit and health probes, and the PageRewind images are signed
by this repository's CI (see [Verify the images](#verify-the-images)).

## Try it on kind

Needs Docker, [kind](https://kind.sigs.k8s.io/), kubectl and Helm 3.8 or newer. The cluster needs about
5 GB of memory. `*.localtest.me` names always resolve to 127.0.0.1, so no DNS setup is needed.

```bash
# 1. A cluster with ports 80 and 443 on localhost, and an ingress controller.
cat <<'EOF' | kind create cluster --name pagerewind --config -
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - {containerPort: 80, hostPort: 80}
      - {containerPort: 443, hostPort: 443}
EOF
kubectl apply -f https://kind.sigs.k8s.io/examples/ingress/deploy-ingress-nginx.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s

# 2. PageRewind (until v0.2.0 is released, use the latest main build).
git clone https://github.com/Yohan-Senadheera/pagerewind && cd pagerewind
helm install pagerewind charts/pagerewind -n pagerewind --create-namespace \
  --set image.tag=main --set ingress.className=nginx --wait --timeout 15m
```

The install prints how to create a login. Then open:

| | |
|---|---|
| Web UI | http://pagerewind.localtest.me |
| Status page | http://status.localtest.me |
| Caught emails (Mailpit) | `kubectl -n pagerewind port-forward svc/pagerewind-mailpit 8025` then http://localhost:8025 |

Send a test alert:

```bash
INGEST_URL=http://ingest.localtest.me \
INGEST_TOKEN=$(kubectl -n pagerewind get secret pagerewind-secrets -o jsonpath='{.data.ingest-token}' | base64 -d) \
  ./scripts/send-test-alert.sh firing
```

The status page shows checkout as a major outage and the incident appears in the web UI. To point
Prometheus Alertmanager at PageRewind, add a webhook receiver for
`http://pagerewind-ingest.pagerewind.svc/v1/alerts/alertmanager` with the ingest token as a bearer token.

Clean up with `kind delete cluster --name pagerewind`.

## The flight recorder

The `capture` service records only namespaces that opt in:

```bash
kubectl label namespace my-app pagerewind.dev/record=true
```

It keeps the last 15 minutes of container logs in memory (secrets masked, 128 MB by default), keeps
the lines of crashed and deleted pods for 30 minutes, and watches for changes such as image updates
and rollbacks. When an incident opens for a workload, these are attached as evidence with the most
likely cause. Its access is read-only (`capture.rbac.create` creates the ClusterRole; it cannot read
Secrets). Set `capture.enabled: false` to leave it out.

## Production

```yaml
publicUrl: https://pagerewind.example.com
auth:
  issuer: https://auth.example.com/realms/pagerewind          # your Keycloak, realm imported from deploy/keycloak
  internalRealmUrl: http://keycloak.keycloak.svc:8080/realms/pagerewind
secrets:
  existingSecret: pagerewind-secrets                          # created by you or External Secrets
postgresql: {bundled: false, host: pagerewind-db-rw.databases.svc}
rabbitmq: {bundled: false, host: rabbitmq.messaging.svc}
redis: {bundled: false, host: redis.cache.svc}
mail: {bundled: false, host: smtp.example.com, port: 587}
keycloak: {bundled: false}
image:
  tag: sha-41a4f5b
  digests: {incident: "sha256:..."}                           # optional: pin exact images
ingress:
  className: nginx
  annotations: {cert-manager.io/cluster-issuer: letsencrypt}
  hosts: {app: pagerewind.example.com, auth: "", status: status.example.com,
          ingest: ingest.example.com, notify: notify.example.com}
  tls: [{secretName: pagerewind-tls, hosts: [pagerewind.example.com, status.example.com,
         ingest.example.com, notify.example.com]}]
```

A full example is in [`ci/external-values.yaml`](ci/external-values.yaml). `values.schema.json`
rejects unknown keys and wrong types, so a typo fails at install time instead of being ignored.

### Secrets

Without `secrets.existingSecret`, the chart creates `<release>-secrets`, generating each value once and
keeping it on upgrades and after uninstall (the database volume still needs the same password).
`helm template` and GitOps tools such as Argo CD cannot see the existing Secret and would generate new
values on every render, so with them create the Secret yourself. It needs these keys:

| Key | Used by |
|---|---|
| `postgres-password`, `rabbitmq-password` | all services |
| `ingest-token` | Alertmanager and other alert senders |
| `action-link-secret` | signing the acknowledge links in emails |
| `web-client-secret`, `notify-client-secret`, `automation-client-secret` | Keycloak clients `pagerewind-web`, `notify-svc`, `pagerewind-automation` |
| `keycloak-admin-password` | bundled Keycloak only |
| `telegram-bot-token`, `telegram-webhook-secret` | optional, for Telegram pages |

## Verify the images

```bash
cosign verify ghcr.io/yohan-senadheera/pagerewind-incident:sha-41a4f5b \
  --certificate-identity-regexp '^https://github\.com/Yohan-Senadheera/pagerewind/\.github/workflows/images\.yml@refs/(heads/main|tags/v[0-9.]+)$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Each image also carries a signed SPDX SBOM (`cosign verify-attestation --type spdxjson ...`). A Kyverno
`ImageValidatingPolicy` that enforces this at admission is in `platform/policies/require-signed-images.yaml`.

## Values

See [`values.yaml`](values.yaml); every value is commented there.
