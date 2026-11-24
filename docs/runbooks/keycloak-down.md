# Runbook: Keycloak down

Keycloak (namespace `keycloak`, run by the Keycloak operator, database by CloudNativePG) signs people in
and issues tokens. It is not on the alerting path.

## Impact

| Still works | Stops |
|---|---|
| Alerts in (static ingest token), incidents, escalation, Telegram and email pages | New sign-ins to the web UI (`503` on auth.pagerewind.dev) |
| API calls with a token issued before the outage, until it expires | New tokens for scripts and services |
| Status page | Acknowledge from Telegram once notify's service token expires (about 5 minutes) |

## Check

```bash
kubectl -n keycloak get keycloak,pods
kubectl -n keycloak get cluster keycloak-db           # CloudNativePG database ready?
kubectl -n keycloak logs keycloak-0 --tail=50
curl -s -o /dev/null -w '%{http_code}\n' https://auth.pagerewind.dev/realms/pagerewind/.well-known/openid-configuration
```

## Fix

- Pod crash-looping on the database: fix `keycloak-db` first (its pods, disk, `kubectl cnpg status` if
  installed).
- Pod pending: check the node and memory (`kubectl describe pod keycloak-0`).
- Realm missing after a database loss: the realm is imported again from `platform/keycloak/realm-import.yaml`
  (generated from `deploy/keycloak/`); people's logins must be created again (`scripts/demo/keycloak-users.ps1`).

## To simulate it

A deny-all NetworkPolicy does not work: the operator's `keycloak-network-policy` allows Keycloak's port, and
NetworkPolicies only add allowed traffic. Stop the workload instead (scale the Keycloak resource to 0 with
Argo CD self-heal paused). Drill: `docs/postmortems/2026-11-24-drill-keycloak-down.md`.
