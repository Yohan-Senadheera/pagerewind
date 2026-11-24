# Architecture

Status: matches v0.2.0 (24 Nov 2026). Parts not built yet are marked as planned.

PageRewind has two parts:

1. **The product:** the PageRewind services that users install (Docker Compose or Helm). No cloud
   provider is required.
2. **The reference deployment:** how the project itself is built, tested, deployed and monitored on
   AWS. Users do not need it; it lives in `infra/`, `platform/` and `deploy/pagerewind/`.

## 1. Big picture

Who talks to PageRewind, and why.

```mermaid
flowchart LR
    subgraph Cluster["Kubernetes cluster"]
        Apps["Workloads"]
        KAPI["Kubernetes API"]
    end

    Prom["Prometheus + Alertmanager"]
    PR["PageRewind"]
    KC["Keycloak (login)"]
    Phone["On-call engineer<br/>Telegram / email"]
    Web["Responders<br/>web UI"]
    Public["Customers<br/>status page"]

    Apps -->|metrics| Prom
    Prom -->|"alert webhook (HMAC)"| PR
    KAPI -->|"logs, events, changes (read-only)"| PR
    PR -->|page| Phone
    Phone -->|"ack / resolve link"| PR
    Web -->|"HTTPS + token"| PR
    Web -.->|login| KC
    PR -->|status| Public
```

## 2. Alert journey

What happens from the moment something breaks.

```mermaid
sequenceDiagram
    autonumber
    participant AM as Alertmanager
    participant IN as ingest
    participant MQ as RabbitMQ
    participant INC as incident
    participant CAP as capture
    participant SCH as schedule
    participant NOT as notify
    actor A as On-call (A)
    actor B as Backup (B)

    AM->>IN: Alert (sent many times)
    IN->>IN: Check signature, drop duplicates
    IN->>MQ: AlertReceived
    MQ->>INC: AlertReceived
    INC->>INC: Open one incident (or attach to the open one)
    INC->>MQ: IncidentTriggered
    par Evidence
        MQ->>CAP: IncidentTriggered
        CAP->>CAP: Freeze flight-recorder window, find recent changes
        CAP->>MQ: EvidenceCaptured (logs, events, likely cause)
        MQ->>INC: Attach to timeline
    and Paging
        MQ->>SCH: IncidentTriggered
        SCH->>SCH: Who is on call? Start escalation timer
        SCH->>MQ: PageRequested (A)
        MQ->>NOT: PageRequested
        NOT->>A: Telegram page
        Note over SCH: No ack in 5 minutes
        SCH->>MQ: PageRequested (B)
        MQ->>NOT: PageRequested
        NOT->>B: Telegram page
    end
    B->>NOT: Tap "Acknowledge"
    NOT->>INC: Acknowledge
    INC->>MQ: IncidentAcknowledged
    MQ->>SCH: Stop escalation
```

## 3. Inside PageRewind

The services, how they talk, and where data lives.

```mermaid
flowchart TB
    User["Browser / API client"] -->|HTTPS| GW["gateway<br/>routing, token check, rate limit"]
    AMW["Alertmanager / Grafana webhooks"] -->|HTTPS + HMAC| GW

    GW --> IN["ingest"]
    GW --> INC["incident<br/>(API + web UI)"]
    GW --> SCH["schedule"]
    NOTW["Telegram button callbacks"] -->|HTTPS| NOT["notify"]

    subgraph Bus["RabbitMQ (events, outbox pattern)"]
        EX(("pagerewind.events"))
    end

    IN --> EX
    INC <--> EX
    SCH <--> EX
    NOT <--> EX
    CAP["capture<br/>flight recorder + what changed"] <--> EX
    EX --> ST["status"]

    IN --- R[("Redis<br/>duplicate filter")]
    INC --- PG[("PostgreSQL<br/>one schema per service")]
    SCH --- PG
    NOT --- PG
    CAP --- PG
    CAP -->|"watch (read-only)"| K8S["Kubernetes API"]
    NOT --> TG["Telegram / SMTP"]

    KC["Keycloak"] -.->|"tokens for users and services"| GW
```

Key rules:

- **No lost or doubled events:** each service writes its change and an outbox row in one database
  transaction; a publisher sends outbox rows to RabbitMQ. Consumers record each event id and skip
  repeats.
- **One incident per problem:** a unique database index on open incidents per fingerprint is the real
  guarantee; Redis is only a fast first filter.
- **Timers survive restarts:** escalation steps are stored in PostgreSQL, not in memory.
- **Least privilege:** `capture` can only read pods, logs, events and workload objects; it cannot
  exec into pods or read secrets.

### Logins and tokens (built in Day 4, see ADR 0009)

```mermaid
sequenceDiagram
    autonumber
    actor P as Person (browser)
    participant GW as gateway
    participant KC as Keycloak
    participant INC as incident
    participant NOT as notify

    P->>GW: GET /ui/incidents/42
    GW-->>P: Redirect to Keycloak (code flow + PKCE)
    P->>KC: Username and password
    KC-->>P: Redirect back with a code
    P->>GW: Code
    GW->>KC: Code + client secret
    KC-->>GW: ID token, access token (roles, audience pagerewind-api)
    GW->>INC: GET /ui/incidents/42 + access token + CSRF token header
    INC-->>P: Page (Acknowledge only for responders), via the gateway
    Note over P,GW: The browser only holds an HttpOnly session cookie
    NOT->>KC: Client credentials (notify-svc)
    KC-->>NOT: Access token (role responder)
    NOT->>INC: POST /v1/incidents/42/acknowledge {"actor":"alice"} + token
```

| Caller | How it proves who it is | What it may do |
|---|---|---|
| Person in the browser | Keycloak login, session at the gateway | By role: viewer reads, responder acts on incidents, admin changes schedules |
| Script or CI | `pagerewind-automation` token (client credentials) | admin |
| `notify` | `notify-svc` token (client credentials) | responder, recorded as the person who was paged |
| Alertmanager | Bearer token or HMAC signature at `ingest` | Send alerts (rate-limited at the gateway) |
| Telegram, email links | Telegram secret header, signed single-use links | Acknowledge or resolve one incident |

## 4. Flight recorder and "what changed"

```mermaid
flowchart LR
    subgraph Recording["Always running (opted-in namespaces)"]
        W["Watch pods, logs, events"] --> RB["Rolling buffer<br/>last 15 min per workload<br/>memory-capped, redacted"]
        C["Watch Deployments, ReplicaSets,<br/>ConfigMaps, HPA, nodes, Argo CD"] --> CE[("change_event table<br/>7 days")]
    end

    ALERT["IncidentTriggered"] --> F["Freeze matching buffers<br/>(incl. deleted pods' last lines)"]
    RB --> F
    F --> B[("Evidence bundle (zip)<br/>in PostgreSQL, 30 days")]

    ALERT --> Q["Changes in the hour before"]
    CE --> Q
    Q --> RANK["Rank: same workload > same namespace > cluster,<br/>closer in time = higher"]
    RANK --> LC["Likely cause on the incident"]
```

Ranking also reads each container's image history in time order, so a revert, a rollback and the
re-deploy of an image that was rolled back earlier are scored differently. Planned, not built: Kubernetes
events and previous-container logs in the bundle, a metrics snapshot, evidence in S3.

## 5. Reference deployment (platform)

How the project is built and run. This is the DevOps side of the project.

```mermaid
flowchart LR
    Dev["Developer"] -->|pull request| GH["GitHub"]

    subgraph CI["GitHub Actions"]
        T["Build + tests<br/>(Testcontainers), gitleaks"] --> S["Trivy gate, cosign sign,<br/>signed SBOM"]
    end
    GH --> CI
    S -->|images| GHCR["GHCR"]
    S -->|"deploy pull request<br/>(tag@digest)"| GIT["deploy/ in Git"]

    TF["Terraform"] -->|"VPC, EC2, IAM, S3"| AWS

    subgraph AWS["AWS (ap-southeast-2)"]
        subgraph K3S["k3s on EC2"]
            ARGO["Argo CD"] --> PRODNS["pagerewind<br/>status: Argo Rollouts canary"]
            MON["Prometheus, Alertmanager,<br/>Grafana"]
            POL["Kyverno (signed images only),<br/>cert-manager, External Secrets,<br/>Istio ambient (mTLS), Keycloak"]
            BJ["Backup CronJobs"]
        end
        SM["Secrets Manager"]
        BK[("S3: database backups")]
    end

    GIT --> ARGO
    GHCR --> PRODNS
    MON -->|"SLO burn alerts"| PRODNS
    MON -.->|"watchdog ping,<br/>critical self-alerts"| HC["healthchecks.io"]
    SM --> POL
    BJ --> BK
```

- **GitOps:** Git holds what should run; Argo CD makes the cluster match.
- **Supply chain:** every image is scanned, signed keylessly and carries a signed SBOM; Kyverno lets only
  images signed by this repository's CI run; deploys pin images by digest.
- **Canary releases:** a new `status` version gets one of two pods; Prometheus checks its error rate and
  Argo Rollouts rolls back automatically above 5%.
- **Monitors itself:** SLO burn-rate alerts and processing-failure alerts go to PageRewind; critical
  ones also to healthchecks.io, which emails without depending on PageRewind; an always-firing
  Watchdog makes healthchecks.io email if alerting goes silent.
- **Backups:** pg_dump to S3 every 6 hours (the cluster cannot delete them); a weekly restore test.
- Planned, not built: preview environments per pull request, logs and traces (Loki, Tempo).

Runbooks and drill write-ups: see the [documentation index](index.md#where-to-go-next).
