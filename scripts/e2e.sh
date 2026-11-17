#!/usr/bin/env bash
# End-to-end test of the Compose quickstart, as a user would run it:
# alert (5 copies) -> one incident -> Alice emailed -> no ack for 1 minute -> Bob emailed ->
# acknowledge through the signed email link -> escalation stops -> resolve -> status page recovers.
set -euo pipefail
cd "$(dirname "$0")/.."

INCIDENT=http://localhost:8082
SCHEDULE=http://localhost:8083
STATUS=http://localhost:8085
MAIL=http://localhost:8025

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok:   $*"; }
retry() { local tries=$1; shift; for _ in $(seq 1 "$tries"); do if "$@" > /dev/null 2>&1; then return 0; fi; sleep 2; done; return 1; }

ready() { curl -fsS "http://localhost:$1/actuator/health/readiness"; }
for port in 8081 8082 8083 8084 8085; do
  retry 90 ready "$port" || fail "service on port $port did not become ready"
done
pass "all five services are ready"

./scripts/seed-demo.sh > /dev/null
pass "demo team, rotation and policy created"

fingerprint="e2e-$(date +%s)"
for _ in 1 2 3 4 5; do ./scripts/send-test-alert.sh firing "$fingerprint" > /dev/null; done
pass "sent the same alert 5 times"

incidents() { curl -fsS "$INCIDENT/v1/incidents?limit=200" | jq --arg fp "alertmanager:$fingerprint" '[.[] | select(.fingerprint == $fp)]'; }
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

confirm=$(curl -fsS "$ack_url")
grep -q '<form method="post">' <<< "$confirm" || fail "the link did not show a confirm page"
[ "$(curl -fsS "$INCIDENT/v1/incidents/$id" | jq -r '.incident.status')" = TRIGGERED ] \
  || fail "opening the link must not acknowledge"
curl -fsS -X POST "$ack_url" | grep -q 'Incident acknowledged.' || fail "acknowledging through the link failed"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ack_url")" = 409 ] || fail "the link worked twice"
[ "$(curl -fsS "$INCIDENT/v1/incidents/$id" | jq -r '.incident.status')" = ACKNOWLEDGED ] || fail "incident not acknowledged"
pass "acknowledged through the signed email link (works once, GET does not act)"

escalation_stopped() { [ "$(curl -fsS "$SCHEDULE/v1/escalations/$id" | jq -r '.escalation.status')" = STOPPED ]; }
retry 30 escalation_stopped || fail "escalation did not stop after the acknowledge"
pass "escalation stopped"

checkout_level() { curl -fsS "$STATUS/v1/status" | jq -r '.services[] | select(.service == "checkout") | .level'; }
is_level() { [ "$(checkout_level)" = "$1" ]; }
retry 30 is_level MAJOR_OUTAGE || fail "status page should show a major outage, shows $(checkout_level)"
pass "status page shows checkout as major outage"

curl -fsS -X POST "$INCIDENT/v1/incidents/$id/resolve" -H 'Content-Type: application/json' --data '{"actor":"e2e"}' > /dev/null
retry 30 is_level OPERATIONAL || fail "status page did not recover, shows $(checkout_level)"
pass "resolved; status page shows checkout as operational"

echo "End-to-end test passed."
