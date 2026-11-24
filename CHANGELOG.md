# Changelog

## 0.2.0 (24 Nov 2026)

### Added

- **Flight recorder and "what changed"** (`capture` service): recent logs of opted-in namespaces, including
  pods that crashed and were deleted; recorded image and config changes; an evidence bundle and a likely
  cause on each new incident (reverts, rollbacks and re-deploys told apart).
- **Logins and roles**: Keycloak sign-in through the new `gateway` (PKCE, CSRF checks, rate limits); viewer,
  responder and admin roles on every API; services call each other with their own tokens.
- Incidents show repeat occurrences of an alert with exact times, and their evidence.
- The status page lists the incidents of the last 14 days.
- **Helm chart** (`charts/pagerewind`) with bundled or external data stores and Keycloak, a values schema
  and a read-only RBAC toggle for the flight recorder; installed on kind in CI.
- Signed images: Trivy gate, keyless cosign signatures and signed SPDX SBOMs; gitleaks on every pull
  request.
- Fault injection setting for rehearsing bad releases (`pagerewind.status.fault-percent`, off by default).

### Changed

- The RabbitMQ Java client is 5.38.0 (Spring Boot's 5.30.0 had four fixable HIGH CVEs).
- The Compose quickstart now needs Keycloak and signs people in.

### Reference deployment

Keycloak, Kyverno, External Secrets, Istio ambient mTLS, Prometheus with SLO burn-rate alerts, backups to
S3 with a restore test, Argo Rollouts canary releases and three failure drills with post-mortems
([docs/postmortems/](docs/postmortems/)).

## 0.1.0 (17 Nov 2026)

First release: alert intake, incidents, on-call schedules and escalation, Telegram and email pages, status
page, Docker Compose quickstart.
