# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub:
[Report a vulnerability](https://github.com/Yohan-Senadheera/pagerewind/security/advisories/new).
Do not open a public issue. You will get an answer within 7 days; fixes for confirmed problems go into a
patch release, and the advisory credits you unless you prefer otherwise.

## Supported versions

| Version | Supported |
|---|---|
| 0.2.x | Yes |
| 0.1.x | No (upgrade to 0.2) |

## What is in place

- Every image is scanned with Trivy in CI (fixable HIGH or CRITICAL vulnerabilities fail the build),
  signed keylessly with cosign and given a signed SPDX SBOM. How to verify:
  [charts/pagerewind/README.md](charts/pagerewind/README.md#verify-the-images).
- gitleaks scans every pull request; GitHub secret scanning and push protection are on.
- Containers run as non-root with a read-only root filesystem and no Linux capabilities.
- Every API checks a Keycloak token (issuer, audience, role); webhooks need a bearer token or HMAC
  signature; email action links are signed and single-use.
- The flight recorder (`capture`) has read-only Kubernetes access and cannot read Secrets; log lines are
  masked for common secret formats before they are stored. See
  [docs/security/capture-rbac.md](docs/security/capture-rbac.md).
