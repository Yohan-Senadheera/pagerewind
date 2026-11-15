# 3. Reference deployment: k3s on one EC2 node

- Status: accepted
- Date: 2026-11-14

## Context

The reference deployment is where PageRewind itself is built, deployed and monitored: GitOps,
preview environments, canary releases, and the monitoring stack. It runs on a personal AWS project
with a small budget, in the selected Region `ap-southeast-2`. The development laptop does not have
enough memory to run everything locally.

Rough memory needs: seven JVM services, Keycloak, PostgreSQL, RabbitMQ, Redis, Argo CD, Prometheus,
Grafana, Loki, Tempo and a service mesh. Together this is well over 4 GB and fits in about 16 GB.

## Decision

- Run **k3s** (a lightweight, certified Kubernetes distribution) on **one EC2 `t3.xlarge`** (4 vCPU,
  16 GB), created by Terraform.
- Stop the instance when not in use (`make stop`), and destroy everything when not needed
  (`make down`). Everything can be recreated from Git with `make up`.
- Keep the design portable: nothing in the product depends on k3s or AWS. The Helm chart must also
  work on kind and other Kubernetes distributions.

## Alternatives considered

- **Amazon EKS:** managed control plane and closer to many production setups, but the control plane
  alone costs about 0.10 USD per hour before any nodes, and it may not be available on every account
  plan. Kept as an optional later demo.
- **Local cluster (kind or k3d) on the laptop:** free, but the laptop runs out of memory; also no
  public HTTPS for webhooks and Telegram callbacks.
- **Several smaller nodes:** closer to a real cluster and more fault tolerant, but costs more and
  adds work that this project does not need yet.

## Consequences

- About 0.17 USD per hour while running; budget alerts and daily cost checks are required.
- A single node is a single point of failure: this is accepted for a reference deployment and
  documented as a limitation. Backups go to S3 so data survives node loss.
- Kubernetes skills transfer to other distributions, because only standard Kubernetes APIs are used.
- The node needs an IAM instance profile for S3 and Secrets Manager; no static AWS keys are stored.
