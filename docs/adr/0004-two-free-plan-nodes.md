# 4. Reference deployment: two free-plan nodes instead of one large node

- Status: accepted
- Date: 2026-11-16
- Supersedes: [0003](0003-k3s-on-one-ec2-node.md)

## Context

ADR 0003 chose k3s on one EC2 `t3.xlarge` (16 GB). The AWS project for the reference deployment is on
the AWS free plan (100 USD of credits). On that plan, only free-tier-eligible instance types can be
used. In `ap-southeast-2` the largest is `m7i-flex.large` (2 vCPU, 8 GB); `t3.xlarge` is not
available.

The stack still needs about 16 GB of memory (see ADR 0003).

## Decision

- Run k3s on **two `m7i-flex.large` nodes** (16 GB in total): one server node (control plane and
  workloads) and one agent node (workloads), created by Terraform in one public subnet.
- Spread memory-heavy parts across nodes with resource requests and anti-affinity (for example the
  monitoring stack on one node, Keycloak and the database on the other).
- Stop both nodes when not in use; `make down` destroys everything.
- Stay on the free plan; credits pay for the build.

## Alternatives considered

- **Upgrade to the paid plan and keep `t3.xlarge`:** simplest, but bills a card once credits run out.
- **One `m7i-flex.large` (8 GB):** cheapest, but tracing, the service mesh and preview environments
  would have to be removed.
- **Several small nodes (`t3.small`, 2 GB):** most nodes for the money, but too little memory per
  node for JVM services plus system pods.

## Consequences

- A real two-node cluster: scheduling, node affinity and node-failure drills become possible (drain
  or stop the agent node and watch workloads move).
- Cost is roughly the same as one `t3.xlarge` (check the EC2 pricing page for `ap-southeast-2`) and
  comes out of the credits. Budget alerts and stopping idle nodes are required.
- The server node is still a single point of failure for the control plane; accepted for a
  reference deployment and documented.
- Local persistent volumes (k3s `local-path`) are tied to one node; PostgreSQL is pinned to a node
  and protected by backups to S3.
- If the account later moves to the paid plan, larger nodes become an option again; that would be a
  new ADR.
