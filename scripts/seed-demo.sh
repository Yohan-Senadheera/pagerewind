#!/usr/bin/env bash
# Creates a demo on-call setup: Alice is on call for "checkout"; if she does not acknowledge
# within 1 minute, Bob is paged. Both get email (see Mailpit). Set TELEGRAM_CHAT_ID to also page
# Alice on Telegram.
set -euo pipefail

SCHEDULE_URL="${SCHEDULE_URL:-http://localhost:8083}"

put() {
  curl -fsS -X PUT -H 'Content-Type: application/json' --data "$2" "$SCHEDULE_URL$1" > /dev/null
  echo "  PUT $1"
}

alice_contacts='{"channel":"EMAIL","address":"alice@example.com"}'
if [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
  alice_contacts="$alice_contacts,{\"channel\":\"TELEGRAM\",\"address\":\"$TELEGRAM_CHAT_ID\"}"
fi

echo "Seeding $SCHEDULE_URL"
put /v1/people/alice "{\"name\":\"Alice\",\"contacts\":[$alice_contacts]}"
put /v1/people/bob '{"name":"Bob","contacts":[{"channel":"EMAIL","address":"bob@example.com"}]}'
put /v1/teams/checkout-team '{"name":"Checkout team","defaultTeam":true,"services":["checkout"]}'
put /v1/teams/checkout-team/rotations/primary \
  "{\"name\":\"Primary\",\"members\":[\"alice\"],\"start\":\"$(date -u +%Y-%m-%d)T00:00:00\",\"timeZone\":\"UTC\",\"shiftHours\":24}"
put /v1/teams/checkout-team/escalation-policy \
  '{"repeat":0,"steps":[{"escalateAfterMinutes":1,"targetType":"ROTATION","targetId":"primary"},{"escalateAfterMinutes":1,"targetType":"PERSON","targetId":"bob"}]}'
echo "Done. On call now:"
curl -fsS "$SCHEDULE_URL/v1/teams/checkout-team/on-call"; echo
