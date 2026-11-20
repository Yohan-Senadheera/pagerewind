# Architecture

Status: planned. This page describes the design; it is updated as each part is built.

PageRewind has two parts:

1. **The product:** the PageRewind services that users install (Docker Compose or Helm). No cloud
   provider is required.
2. **The reference deployment:** how the project itself is built, tested, deployed and monitored on
   AWS. Users do not need it; it lives in `deploy/reference/`.

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
    CAP --- S3[("S3 / MinIO<br/>evidence bundles")]
    CAP -->|"watch (read-only)"| K8S["Kubernetes API"]
    CAP -->|query| PROM["Prometheus"]
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

    ALERT["IncidentTriggered"] --> F["Freeze matching buffers<br/>+ previous-container logs<br/>+ metrics snapshot"]
    RB --> F
    F --> B[("Evidence bundle in S3")]

    ALERT --> Q["Changes in the hour before"]
    CE --> Q
    Q --> RANK["Rank: same workload > same namespace > cluster,<br/>closer in time = higher"]
    RANK --> LC["Likely cause on the incident"]
```

## 5. Reference deployment (platform)

How the project is built and run. This is the DevOps side of the project.

```mermaid
flowchart LR
    Dev["Developer"] -->|pull request| GH["GitHub"]

    subgraph CI["GitHub Actions"]
        T["Build + tests<br/>(Testcontainers)"] --> S["Scan: Trivy, gitleaks<br/>SBOM, cosign sign"]
    end
    GH --> CI
    S -->|images| GHCR["GHCR"]
    S -->|"update image tag"| GIT["deploy/ in Git"]

    TF["Terraform"] -->|"VPC, EC2, IAM, S3"| AWS

    subgraph AWS["AWS (ap-southeast-2)"]
        subgraph K3S["k3s on EC2"]
            ARGO["Argo CD"] --> PRODNS["pagerewind (prod)<br/>Argo Rollouts canary"]
            ARGO --> PREV["pr-N preview namespaces"]
            MON["Prometheus, Grafana,<br/>Loki, Tempo"]
            POL["Kyverno, cert-manager,<br/>External Secrets, Linkerd"]
        end
        SM["Secrets Manager"]
        BK[("S3: backups, evidence")]
    end

    GIT --> ARGO
    GHCR --> PRODNS
    MON -->|"SLO burn alerts"| PRODNS
    MON -.->|"watchdog ping"| HC["healthchecks.io"]
    SM --> POL
```

- **GitOps:** Git holds what should run; Argo CD makes the cluster match.
- **Preview environments:** every pull request gets its own namespace and database.
- **Canary releases:** a new version gets 10% of traffic first; if errors or latency rise, Argo
  Rollouts rolls back automatically.
- **Monitors itself:** PageRewind's own SLO alerts are sent to PageRewind; an external watchdog
  emails if the whole system goes silent.
