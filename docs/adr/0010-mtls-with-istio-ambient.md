# 10. Mutual TLS between services with Istio ambient mode

- Status: accepted
- Date: 2026-11-22

## Context

Traffic between PageRewind services inside the cluster (gateway to incident, notify to incident, every
service to PostgreSQL, RabbitMQ and Redis) was plain text. Anyone who can capture traffic on a node, or
a compromised pod, could read tokens and data. We want every connection encrypted and both sides
identified, without changing application code.

Constraints from earlier decisions:

- Kyverno enforces non-root containers, memory limits and probes in the application namespaces
  (`platform/policies`). A mesh must not need root or extra capabilities inside application pods.
- The reference cluster has two 8 GiB nodes and already runs about 20 application pods.
- Only open-source releases that anyone can install.

## Decision

Use **Istio in ambient mode** (charts `base`, `istiod`, `cni`, `ztunnel`, version 1.30.8, managed by
Argo CD in `platform/apps/istio.yaml`).

- One `ztunnel` per node carries the traffic of every pod in a namespace labelled
  `istio.io/dataplane-mode=ambient` and wraps it in mutual TLS with SPIFFE identities derived from
  the pod's service account. No sidecar and no init container are added to the pods.
- `global.platform=k3s` points the CNI plugin at k3s's CNI folders.
- `istiod` is sized for this cluster (256 MiB request, no autoscaling) instead of the 2 GiB default.
- Peer authentication stays **permissive** at first: connections between pods in the mesh are mutual
  TLS, and Traefik (outside the mesh) can still reach the gateway. Strict mode for the namespace is a
  later step once ingress is part of the mesh.
- Namespaces join one at a time, starting with `pagerewind`.

## Alternatives considered

- **Linkerd:** small and simple, but each meshed pod gets a proxy sidecar and an init container that
  needs `NET_ADMIN` and runs as root (unless the Linkerd CNI plugin is installed). That conflicts with
  the enforced non-root rule. Stable releases now come from Buoyant; the open-source project ships edge
  releases.
- **Istio with sidecars:** same identity model, but a proxy per pod (memory per pod, restarts to
  inject, the same init container issue).
- **Application-level TLS:** certificates in every service and in PostgreSQL, RabbitMQ and Redis;
  much more configuration and no common identity.

## Consequences

- Pods do not change; joining the mesh is a label on the namespace.
- About 150 to 400 MiB more memory in total (istiod, two ztunnels, two CNI agents).
- `ztunnel` and the CNI agent run privileged in `istio-system`; that namespace is outside the Kyverno
  rules on purpose.
- Layer 7 policies (per-path authorization, retries) need a waypoint proxy; not used yet.
