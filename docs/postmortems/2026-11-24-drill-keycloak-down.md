# Post-mortem: drill 3, Keycloak (logins) unavailable

Date: 24 Nov 2026. Type: planned failure drill on the reference cluster. Blameless.

## Summary

The plan was to make Keycloak unreachable for 6 minutes with a deny-all NetworkPolicy and a pod restart.
Keycloak was down only for its restart, about 30 seconds: the Keycloak operator's own NetworkPolicy allows
traffic to Keycloak, and NetworkPolicies only add allowed traffic, so a second, deny-all policy cannot take
that away. In the short outage, signed-in API use and alerting kept working; only new logins and new tokens
failed, as designed.

## What happened (Asia/Colombo)

| Time | API with a token issued before | New token | Login page | Alert |
|---|---|---|---|---|
| 22:12:16 (before) | 200 | ok | 200 | 202 |
| 22:12:17 | deny-all NetworkPolicy on `app=keycloak`; `keycloak-0` deleted | | | |
| 22:13:01 | **200** | **fail** | **503** | **202** |
| 22:13:35 onwards | 200 | ok | 200 | 202 |

## What it shows

- **Logins are not on the alerting path.** Alerts use a static ingest token, and services validate tokens
  with Keycloak's public keys, which they cache. A Keycloak outage stops new sign-ins, not paging.
- **Tokens already issued keep working** until they expire (5 minutes for service tokens), so short
  Keycloak outages are invisible to signed-in users and to service-to-service calls.

## What went wrong in the drill itself

**NetworkPolicies are allow-lists that add up.** Traffic is allowed if any policy that selects the pod
allows it. The Keycloak operator creates `keycloak-network-policy`, which allows its HTTP port, so the
drill's deny-all policy changed nothing. A real block needs the allowing policy itself changed, or the
workload stopped (for example the Keycloak resource scaled to 0 with Argo CD's self-heal paused).

Related lesson from drill 1: in the Istio ambient mesh, meshed pods' traffic rides long-lived ztunnel
tunnels, and pooled connections stay open, so a NetworkPolicy is not a reliable way to cut traffic there
either; use an Istio AuthorizationPolicy.

## Actions

| Action | Status |
|---|---|
| Repeat with a full-length outage (scale Keycloak to 0 with Argo CD self-heal paused) and check that Telegram acknowledge fails once notify's 5-minute token expires | Open |
| Document how to simulate outages correctly (Istio AuthorizationPolicy in the mesh; check existing allow policies outside it) | Done (this file and `docs/runbooks/database-down.md`) |
