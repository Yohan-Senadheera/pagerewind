# 7. CI without AWS credentials

- Status: accepted
- Date: 2026-11-18

## Context

The plan was to let GitHub Actions reach AWS through an IAM OIDC identity provider for
`token.actions.githubusercontent.com`, with a read-only role for pull requests and an apply role for
`main`. No long-lived keys would be stored in GitHub.

The AWS project for the reference deployment uses the new AWS experience. Its managed service control
policy denies `iam:*Provider*`, so `iam:CreateOpenIDConnectProvider` fails with an explicit deny. The
deny applies on both the free and the paid plan. Lifting it needs an upgrade plus "advanced features",
and both are irreversible.

## Decision

GitHub Actions gets no AWS access at all.

- **Application delivery** is pull-based: Argo CD runs in the cluster and syncs from Git. CI only builds
  images and pushes them to GHCR with the built-in `GITHUB_TOKEN`.
- **AWS access from the cluster** (Secrets Manager, S3 backups) uses the nodes' IAM instance profile.
- **Terraform** is applied from the maintainer's laptop with short-lived `aws login` credentials. CI checks
  every pull request without credentials: `terraform fmt -check`, `init -backend=false`, `validate`, and
  later linting and a security scan of the configuration.
- No IAM users or access keys are created for CI.

## Alternatives considered

- **IAM user with access keys in GitHub secrets:** works today, but long-lived keys are the main thing
  this design avoids. A leaked key would give access until someone notices and rotates it.
- **Upgrade and activate advanced features to allow the OIDC provider:** clean technically, but
  irreversible and removes the free plan's spending protection, for a benefit only Terraform needs.
- **Run Terraform inside the cluster (for example a controller using the node role):** removes the laptop
  step, but gives in-cluster workloads the power to change the cluster's own network and IAM.

## Consequences

- No AWS secret can leak from GitHub, because none exists there.
- Infrastructure changes are not applied automatically on merge; they are applied by hand and reviewed
  through `terraform plan`. For a small reference deployment that changes rarely, this is acceptable.
- Drift between Git and AWS is not detected automatically. A manual `terraform plan` is part of the
  release checklist.
- If the account later allows OIDC providers (for example in an organization without this policy), the
  original design can be added without changing the application pipeline.
