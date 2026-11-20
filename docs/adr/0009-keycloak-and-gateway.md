# 9. Logins with Keycloak, a gateway that holds the session, and tokens on every API

- Status: accepted
- Date: 2026-11-20

## Context

v0.1.0 had no login, so it could only run on a private network. PageRewind needs people to sign in, a
few roles (who may only look, who may act on incidents, who may change schedules), and a way for one
service to call another (notify acknowledges in the incident service for the person who tapped the
button). It must stay self-hosted with no paid identity service, and the web pages are rendered on
the server by the services themselves.

## Decision

- **Keycloak** is the identity provider, with the realm stored in Git (`deploy/keycloak`). Secrets and
  the public address are placeholders filled from the environment, so the file holds no secrets.
- **Three roles** in the realm: `viewer`, `responder`, `admin`. The services apply the order
  admin > responder > viewer, so each person needs one role.
- **Every API is an OAuth2 resource server** (shared `libs/security`): it accepts only access tokens
  signed by the realm, with the right issuer, the audience `pagerewind-api`, and not expired.
  `@PreAuthorize` checks the role per action. Probes, metrics and API docs stay open.
- **The gateway holds the browser session** (Spring Cloud Gateway as a backend for frontend). It signs
  people in with the confidential client `pagerewind-web` (authorization code with PKCE), keeps the
  tokens server-side, and forwards the access token to the pages (`TokenRelay`). The browser never sees
  a token, only an HttpOnly session cookie.
- **CSRF:** the gateway checks a CSRF token on every form post. The services render the forms but do
  not hold the session, so the gateway passes its token to them in a header (`X-PageRewind-Csrf`) and
  removes any value the browser sends in that header.
- **`/api/**` is pass-through:** the caller brings its own bearer token and the gateway never attaches
  the session's token there, so cookies cannot be used to call the API cross-site.
- **Service to service:** `notify` gets its own token with client credentials (`notify-svc`, role
  `responder`). A person is always recorded as themselves; only a service account may name the person
  it acts for.
- **Webhooks keep their own checks** (alert signature or token, Telegram's secret, signed links). The
  gateway adds a per-client rate limit in Redis.
- The gateway runs on Spring Boot 4.2 with Spring Cloud 2026.0, the pair Spring Cloud supports. The
  other services stay on Boot 4.1 until springdoc supports 4.2.

## Alternatives considered

- **Asgardeo, WSO2 Identity Server or another hosted identity service:** good products, but a
  self-hosted tool should not depend on a hosted service, and Keycloak's realm export makes the setup
  reviewable in Git.
- **Tokens in the browser (single-page app with PKCE):** puts tokens where scripts can read them and
  needs a JavaScript front end. The pages are server-rendered, so a session at the gateway is simpler
  and safer.
- **Each service signs people in itself:** several sessions and client secrets, and logout would not
  be in one place.
- **SameSite cookies instead of CSRF tokens:** helps, but older browsers and same-site subdomains are
  not covered. The token check costs little.

## Consequences

- The quickstart now runs Keycloak (about 1 GB of memory more) and the UI moves to the gateway on port
  8080.
- Scripts and CI use the `pagerewind-automation` client (admin role) for the API.
- The gateway keeps sessions in memory, so it runs as one replica until sessions move to Redis.
- Keycloak on the reference cluster (operator, PostgreSQL, `auth.<domain>`) is the next step.
