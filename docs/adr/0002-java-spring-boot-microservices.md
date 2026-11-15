# 2. Java and Spring Boot microservices

- Status: accepted
- Date: 2026-11-14

## Context

PageRewind has clearly separate jobs: receiving alerts, managing incidents, on-call schedules,
sending notifications, capturing evidence and a public status page. They have different load and
failure patterns. For example, alert intake must keep working even if the notification provider is
slow, and evidence capture must not slow down paging.

The project also needs a language with strong libraries for security (OIDC, JWT), databases,
messaging and the Kubernetes API, and one that is widely used by the teams who would run it.

## Decision

- **Language and framework:** Java 21 with Spring Boot, built with Maven (multi-module project).
- **Services:** `gateway`, `ingest`, `incident`, `schedule`, `notify`, `capture`, `status`.
  Each owns its data (its own PostgreSQL schema) and can be deployed and scaled on its own.
- **Communication:** asynchronous events through RabbitMQ for the main flow, using the transactional
  outbox pattern and idempotent consumers. Synchronous HTTP only where an immediate answer is needed
  (for example, the web UI reading an incident).
- **Shared code:** only event definitions and common security settings, in `libs/`.

## Alternatives considered

- **One application (modular monolith):** simpler to run and debug, and often the right choice for a
  small tool. Rejected because independent failure and scaling of intake, paging and capture matter
  here, and because the project is also meant to show how to operate several services well. The
  services are kept few and coarse to limit the cost.
- **Go:** smaller memory use and single binaries, common in Kubernetes tools. Rejected because the
  security, data and messaging libraries in the Spring ecosystem save time, and Java is widely used by
  the operations teams this project targets.
- **Kafka instead of RabbitMQ:** better for very high event volumes and replay. Rejected because
  alert volumes are modest, and RabbitMQ is lighter to run and simpler for work-queue patterns.

## Consequences

- Each service is a separate JVM, so memory use is higher than one application: limits per service
  and a 16 GB node for the reference deployment.
- More moving parts to deploy and observe; this is handled with one Helm chart, tracing across
  services, and per-service dashboards.
- Events can arrive twice or out of order; every consumer must be idempotent and handle ordering.
- A Docker Compose file is needed so users can try it without Kubernetes.
