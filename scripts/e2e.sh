#!/usr/bin/env bash
# End-to-end test of the Compose quickstart, as a user would run it:
# alert (5 copies, one through the gateway) -> one incident -> Alice emailed -> no ack for 1 minute ->
# Bob emailed -> acknowledge through the signed email link (notify calls incident with its own token) ->
# escalation stops -> APIs refuse requests without a token -> a viewer signs in and cannot act ->
# Alice signs in through Keycloak and resolves from the web page -> status page recovers.
set -euo pipefail
cd "$(dirname "$0")/.."

GATEWAY=http://localhost:8080
INCIDENT=http://localhost:8082
SCHEDULE=http://localhost:8083
STATUS=http://localhost:8085
MAIL=http://localhost:8025
KEYCLOAK=http://localhost:8180

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok:   $*"; }
retry() { local tries=$1; shift; for _ in $(seq 1 "$tries"); do if "$@" > /dev/null 2>&1; then return 0; fi; sleep 2; done; return 1; }
env_value() { grep -E "^$1=" .env | cut -d= -f2-; }
DEMO_PASSWORD=$(env_value DEMO_PASSWORD)
AUTOMATION_CLIENT_SECRET=$(env_value AUTOMATION_CLIENT_SECRET)

ready() { curl -fsS "http://localhost:$1/actuator/health/readiness"; }
for port in 8080 8081 8082 8083 8084 8085; do
  retry 90 ready "$port" || fail "service on port $port did not become ready"
done
pass "gateway and all five services are ready"

# A fresh admin token for each call (they live 5 minutes).
token() {
  curl -fsS "$KEYCLOAK/realms/pagerewind/protocol/openid-connect/token" -d grant_type=client_credentials \
    -d client_id=pagerewind-automation --data-urlencode "client_secret=$AUTOMATION_CLIENT_SECRET" | jq -r .access_token
}
api() { curl -fsS -H "Authorization: Bearer $(token)" "$@"; }

./scripts/seed-demo.sh > /dev/null
pass "demo logins, team, rotation and policy created"

fingerprint="e2e-$(date +%s)"
for _ in 1 2 3 4; do ./scripts/send-test-alert.sh firing "$fingerprint" > /dev/null; done
INGEST_URL=$GATEWAY ./scripts/send-test-alert.sh firing "$fingerprint" > /dev/null
pass "sent the same alert 5 times (once through the gateway)"

incidents() { api "$INCIDENT/v1/incidents?limit=200" | jq --arg fp "alertmanager:$fingerprint" '[.[] | select(.fingerprint == $fp)]'; }
one_incident_five_alerts() { [ "$(incidents | jq 'length')" = 1 ] && [ "$(incidents | jq '.[0].alertCount')" = 5 ]; }
retry 30 one_incident_five_alerts || fail "expected one incident with 5 alerts, got: $(incidents)"
id=$(incidents | jq -r '.[0].id')
pass "one incident ($id) with 5 alerts"

mails_to() { curl -fsS "$MAIL/api/v1/messages" | jq --arg to "$1" '[.messages[] | select(any(.To[]; .Address == $to))]'; }
has_mail() { [ "$(mails_to "$1" | jq 'length')" -ge 1 ]; }
retry 30 has_mail alice@example.com || fail "Alice was not paged"
pass "Alice (on call) was paged by email"
has_mail bob@example.com && fail "Bob was paged before the 1 minute escalation delay"

retry 60 has_mail bob@example.com || fail "Bob was not paged after Alice did not acknowledge"
pass "Bob was paged after 1 minute without acknowledgement"

message_id=$(mails_to alice@example.com | jq -r '.[0].ID')
text=$(curl -fsS "$MAIL/api/v1/message/$message_id" | jq -r '.Text')
ack_url=$(grep -oE 'Acknowledge: http[^[:space:]]+' <<< "$text" | head -1 | cut -d' ' -f2)
[ -n "$ack_url" ] || fail "no acknowledge link in the email: $text"

status_of() { api "$INCIDENT/v1/incidents/$id" | jq -r '.incident.status'; }
confirm=$(curl -fsS "$ack_url")
grep -q '<form method="post">' <<< "$confirm" || fail "the link did not show a confirm page"
[ "$(status_of)" = TRIGGERED ] || fail "opening the link must not acknowledge"
# Capture first: with pipefail, "curl | grep -q" can fail when grep stops reading before curl is done.
acked=$(curl -fsS -X POST "$ack_url")
grep -q 'Incident acknowledged.' <<< "$acked" || fail "acknowledging through the link failed"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ack_url")" = 409 ] || fail "the link worked twice"
[ "$(status_of)" = ACKNOWLEDGED ] || fail "incident not acknowledged"
[ "$(api "$INCIDENT/v1/incidents/$id" | jq -r '.timeline[] | select(.type == "ACKNOWLEDGED") | .actor')" = alice ] \
  || fail "the acknowledgement should be recorded as Alice's"
pass "acknowledged as Alice through the signed email link (notify used its own token; works once; GET does not act)"

escalation_stopped() { [ "$(api "$SCHEDULE/v1/escalations/$id" | jq -r '.escalation.status')" = STOPPED ]; }
retry 30 escalation_stopped || fail "escalation did not stop after the acknowledge"
pass "escalation stopped"

checkout_level() { curl -fsS "$STATUS/v1/status" | jq -r '.services[] | select(.service == "checkout") | .level'; }
is_level() { [ "$(checkout_level)" = "$1" ]; }
retry 30 is_level MAJOR_OUTAGE || fail "status page should show a major outage, shows $(checkout_level)"
pass "status page shows checkout as major outage"

# ---- logins, roles and tokens

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
[ "$(code "$INCIDENT/v1/incidents/$id")" = 401 ] || fail "incident API without a token must answer 401"
[ "$(code -X POST "$INCIDENT/v1/incidents/$id/resolve")" = 401 ] || fail "resolve without a token must answer 401"
[ "$(code -X PUT -H 'Content-Type: application/json' --data '{"name":"x"}' "$SCHEDULE/v1/teams/checkout-team")" = 401 ] \
  || fail "schedule API without a token must answer 401"
[ "$(code "$GATEWAY/api/v1/incidents")" = 401 ] || fail "gateway API without a token must answer 401"
[ "$(code -H "Authorization: Bearer $(token)" "$GATEWAY/api/v1/incidents/$id")" = 200 ] || fail "gateway API with a token failed"
pass "APIs answer 401 without a token, directly and through the gateway"

# Signs in through the Keycloak login form like a browser; prints the page the gateway lands on.
login() {
  local user=$1 jar=$2 page action
  page=$(curl -fsS -L -H 'Accept: text/html' -c "$jar" -b "$jar" "$GATEWAY/ui/incidents/$id")
  action=$(grep -oE 'action="[^"]*login-actions/authenticate[^"]*"' <<< "$page" | head -1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')
  [ -n "$action" ] || fail "no Keycloak login form: $page"
  curl -fsS -L -H 'Accept: text/html' -c "$jar" -b "$jar" --data-urlencode "username=$user" --data-urlencode "password=$DEMO_PASSWORD" \
    --data-urlencode credentialId= "$action"
}
csrf_in() { grep -oE 'name="_csrf" value="[^"]+"' <<< "$1" | head -1 | cut -d'"' -f4; }
jars=$(mktemp -d)
trap 'rm -rf "$jars"' EXIT

vera_page=$(login vera "$jars/vera")
grep -q 'vera' <<< "$vera_page" || fail "the page does not show vera as signed in"
grep -q 'Ask an admin for the responder role' <<< "$vera_page" || fail "a viewer should see no actions"
vera_csrf=$(csrf_in "$vera_page")
[ -n "$vera_csrf" ] || fail "no CSRF token on the page"
[ "$(code -b "$jars/vera" --data-urlencode "_csrf=$vera_csrf" "$GATEWAY/ui/incidents/$id/resolve")" = 403 ] \
  || fail "a viewer must not be able to resolve"
[ "$(status_of)" = ACKNOWLEDGED ] || fail "the viewer changed the incident"
pass "vera (viewer) signed in through Keycloak, sees no actions, and gets 403 when she tries"

alice_page=$(login alice "$jars/alice")
grep -q '>Resolve<' <<< "$alice_page" || fail "a responder should see the Resolve button"
[ "$(code -b "$jars/alice" -X POST "$GATEWAY/ui/incidents/$id/resolve")" = 403 ] || fail "a form without the CSRF token must be refused"
resolved=$(curl -fsS -L -b "$jars/alice" -c "$jars/alice" --data-urlencode "_csrf=$(csrf_in "$alice_page")" \
  "$GATEWAY/ui/incidents/$id/resolve")
grep -q 'Incident resolved.' <<< "$resolved" || fail "resolving from the web page failed"
[ "$(api "$INCIDENT/v1/incidents/$id" | jq -r '.timeline[] | select(.type == "RESOLVED") | .actor')" = alice ] \
  || fail "the resolve should be recorded as Alice's"
pass "alice (responder) signed in through Keycloak and resolved from the web page (CSRF checked)"

retry 30 is_level OPERATIONAL || fail "status page did not recover, shows $(checkout_level)"
pass "status page shows checkout as operational"

echo "End-to-end test passed."
