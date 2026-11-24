# 11. Signed images, signed SBOMs and digest-pinned deploys

- Status: accepted
- Date: 2026-11-24

## Context

Images were built and pushed by CI, but nothing proved who built an image or what was in it, a pushed
tag could be replaced, and deploying meant editing image tags by hand. We want the cluster to run only
images this repository's CI built and checked, without managing signing keys.

## Decision

- **Scan before push:** Trivy fails the build on fixable HIGH or CRITICAL vulnerabilities (retrying only
  when the scanner itself cannot run).
- **Keyless signing:** cosign signs each image digest with a short-lived Sigstore certificate issued for
  the GitHub Actions workflow identity (`images.yml` on `main` or a `v*` tag); the signature is recorded
  in the public transparency log. A signed SPDX SBOM (Syft) is attached to each image.
- **Admission control:** a Kyverno `ImageValidatingPolicy` refuses PageRewind images without that
  signature. It ran in Audit first.
- **Digest pinning:** deploys name `sha-<commit>@sha256:<digest>`. CI opens the deploy pull request
  (`scripts/pin-images.sh`); merging it is the approval.

## Alternatives considered

- **Key-based cosign signing:** a private key to store, rotate and protect; keyless ties the signature
  to the workflow instead.
- **Tag-only deploys:** readable, but a tag can point to different bytes after it was checked. The audit
  run showed every pod failing "does not have a digest".
- **Automatic deploys without a pull request:** faster, but no review point; the deploy PR keeps a human
  approval and a Git record.

## Consequences

- An unsigned image (v0.1.0, built before signing) is refused at admission; signed images run.
- The first scan found four fixable HIGH CVEs in the RabbitMQ Java client pinned by Spring Boot; the
  version is overridden in the root pom.
- Admission depends on reaching GHCR and Sigstore; Kyverno's webhook is fail-closed, so an outage of those
  blocks new PageRewind pods (existing pods keep running).
- Pull requests opened by the workflow's token do not trigger other workflows, so deploy PRs show no
  checks; their images were checked before the PR existed.
