# Getting started: your team on call in 20 minutes

After [installing](install.md) PageRewind with Helm, these steps take you from an empty install to a real
page on your phone. Commands assume the release is called `pagerewind` in namespace `pagerewind`, and a
bash shell with `curl` and `jq`.

Setup happens through the REST API for now; the web UI shows incidents and who is on call, but has no setup
screens yet.

## 1. Sign in as an admin

The bundled Keycloak starts without people. Create yourself with the `admin` role:

```bash
kubectl -n pagerewind exec deploy/pagerewind-keycloak -- sh -c '
  /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master \
    --user admin --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" &&
  /opt/keycloak/bin/kcadm.sh create users -r pagerewind -s username=me -s enabled=true \
    -s firstName=Me -s lastName=Admin -s email=me@example.com -s emailVerified=true &&
  /opt/keycloak/bin/kcadm.sh set-password -r pagerewind --username me --new-password change-me-now &&
  /opt/keycloak/bin/kcadm.sh add-roles -r pagerewind --uusername me --rolename admin'
```

Roles: `viewer` reads; `responder` also acknowledges, resolves and adds notes; `admin` also changes teams and
schedules. The bundled Keycloak keeps its data in the pod: people are lost when it restarts. For lasting use,
run your own Keycloak (see [Install](install.md) and the chart's production values).

## 2. Get an API token

Scripts use the `pagerewind-automation` client, which has the admin role.

```bash
kubectl -n pagerewind port-forward svc/pagerewind-keycloak 8180:8080 >/dev/null &
kubectl -n pagerewind port-forward svc/pagerewind-schedule 8083:80 >/dev/null &

SECRET=$(kubectl -n pagerewind get secret pagerewind-secrets -o jsonpath='{.data.automation-client-secret}' | base64 -d)
TOKEN=$(curl -s http://localhost:8180/realms/pagerewind/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=pagerewind-automation \
  --data-urlencode "client_secret=$SECRET" | jq -r .access_token)
put() { curl -fsS -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
          --data "$2" "http://localhost:8083$1"; echo; }
```

Tokens last 5 minutes; run the `TOKEN=` line again if a call answers `401`.

## 3. Describe your team

Ids (`ana`, `payments-team`, `primary`) are yours to choose: up to 63 lowercase letters, digits, dots,
underscores and dashes, starting with a letter or digit. A `PUT`
creates or replaces, so you can run the same commands again after a change.

```bash
# People and how to reach them (EMAIL and/or TELEGRAM; Telegram needs step 5)
put /v1/people/ana   '{"name":"Ana","contacts":[{"channel":"EMAIL","address":"ana@example.com"}]}'
put /v1/people/ben   '{"name":"Ben","contacts":[{"channel":"EMAIL","address":"ben@example.com"}]}'

# A team and the services it owns. Alerts are matched to a team by their "service" label.
# "defaultTeam": true also catches alerts whose service no team owns.
put /v1/teams/payments-team '{"name":"Payments team","defaultTeam":true,"services":["checkout","payments"]}'

# A rotation: members take turns, each shift lasting shiftHours, starting at "start" in timeZone
put /v1/teams/payments-team/rotations/primary \
  '{"name":"Primary","members":["ana","ben"],"start":"2026-12-01T09:00:00","timeZone":"Asia/Colombo","shiftHours":168}'

# Who is paged, in order: the on-call person of the rotation, then Ben if nobody acknowledged
# within 5 minutes. "repeat" runs the whole policy again that many times.
put /v1/teams/payments-team/escalation-policy \
  '{"repeat":1,"steps":[{"escalateAfterMinutes":5,"targetType":"ROTATION","targetId":"primary"},{"escalateAfterMinutes":5,"targetType":"PERSON","targetId":"ben"}]}'

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8083/v1/teams/payments-team/on-call
```

Someone covering a shift: `POST /v1/teams/payments-team/rotations/primary/overrides` with
`{"personId":"ben","start":"...","end":"..."}` (times in UTC, ISO 8601).

## 4. Email

The bundled Mailpit catches every email instead of sending it (useful for trying out:
`kubectl -n pagerewind port-forward svc/pagerewind-mailpit 8025` then http://localhost:8025).

To really send email, point the chart at an SMTP relay and set the sender address:

```yaml
mail:
  bundled: false
  host: smtp-relay.example.com
  port: 25
services:
  notify:
    emailFrom: oncall@example.com
    publicUrl: https://notify.example.com    # used in the acknowledge links in emails
```

The chart does not pass SMTP login settings yet, so the relay must accept mail from the cluster without a
login (for example an internal relay, or a provider's IP-allowlisted relay).

## 5. Telegram

1. **Create a bot:** in Telegram, talk to `@BotFather`, send `/newbot`, and keep the token it gives you.
2. **Find each person's chat id:** each person opens a chat with your bot and sends any message. Then open
   `https://api.telegram.org/bot<token>/getUpdates` in a browser; the number in `"chat":{"id": ...}` is their
   chat id. Do this before step 4 below: once a webhook is set, `getUpdates` stops answering.
3. **Give PageRewind the token,** in a values file (not on the command line, so it stays out of your shell
   history), then upgrade:
   ```yaml
   # telegram.yaml - keep this file out of Git
   secrets:
     values:
       telegram-bot-token: "123456:ABC..."
       telegram-webhook-secret: "a-long-random-string"   # openssl rand -hex 24
   ```
   ```bash
   helm upgrade pagerewind oci://ghcr.io/yohan-senadheera/charts/pagerewind --version 0.2.0 \
     -n pagerewind --reuse-values -f telegram.yaml
   kubectl -n pagerewind rollout restart deploy/pagerewind-notify
   ```
4. **Let the buttons work (needs a public HTTPS address for notify):** Telegram sends button presses to
   `https://<notify host>/v1/telegram/webhook`. Register it once:
   ```bash
   curl -s "https://api.telegram.org/bot<token>/setWebhook" -H 'Content-Type: application/json' \
     -d '{"url":"https://notify.example.com/v1/telegram/webhook","secret_token":"<the webhook secret>","allowed_updates":["callback_query"]}'
   ```
   Without a public address (for example on kind), pages still arrive; acknowledge from the web UI instead.
5. **Add the chat id as a contact:**
   ```bash
   put /v1/people/ana '{"name":"Ana","contacts":[{"channel":"EMAIL","address":"ana@example.com"},{"channel":"TELEGRAM","address":"123456789"}]}'
   ```

## 6. Send alerts from Alertmanager

```yaml
# alertmanager.yml
receivers:
  - name: pagerewind
    webhook_configs:
      - url: http://pagerewind-ingest.pagerewind.svc/v1/alerts/alertmanager
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials_file: /etc/alertmanager/secrets/pagerewind/ingest-token
```

The ingest token: `kubectl -n pagerewind get secret pagerewind-secrets -o jsonpath='{.data.ingest-token}' | base64 -d`.

Labels PageRewind uses on each alert:

| Label | Used for |
|---|---|
| `service` | Which team is paged (the team that owns this service) and the status page |
| `severity` | `critical` shows as a major outage on the status page, anything else as degraded |
| `namespace` and `deployment` (or `pod`) | Which workload the flight recorder attaches evidence for |

Repeats of the same alert (same fingerprint) are added to the open incident instead of opening a new one.

## 7. Turn on the flight recorder

```bash
kubectl label namespace my-app pagerewind.dev/record=true
```

From then on, incidents for workloads in `my-app` get an evidence bundle (recent logs, including pods that
already crashed, and recent changes) and a likely cause.

## 8. Try it

```bash
kubectl -n pagerewind port-forward svc/pagerewind-ingest 8081:80 >/dev/null &
INGEST_URL=http://localhost:8081 \
INGEST_TOKEN=$(kubectl -n pagerewind get secret pagerewind-secrets -o jsonpath='{.data.ingest-token}' | base64 -d) \
  ./scripts/send-test-alert.sh firing     # from a clone of the repository
```

The on-call person is paged; acknowledge from the page or the web UI, and the escalation stops. If nothing
arrives, check `kubectl -n pagerewind logs deploy/pagerewind-notify` and the [runbooks](index.md#where-to-go-next).
