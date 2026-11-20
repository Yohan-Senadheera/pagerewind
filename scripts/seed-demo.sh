#!/usr/bin/env bash
# Creates a demo on-call setup for the Compose quickstart:
#   - logins alice and bob (responders) and vera (viewer), all with DEMO_PASSWORD from .env
#   - Alice is on call for "checkout"; if she does not acknowledge within 1 minute, Bob is paged.
# Both get email (see Mailpit). Set TELEGRAM_CHAT_ID to also page Alice on Telegram.
set -euo pipefail
cd "$(dirname "$0")/.."

SCHEDULE_URL="${SCHEDULE_URL:-http://localhost:8083}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"

env_value() {
  local value="${!1:-}"
  if [ -z "$value" ] && [ -f .env ]; then
    value=$(grep -E "^$1=" .env | cut -d= -f2-)
  fi
  [ -n "$value" ] || { echo "Set $1 (or run from the folder with .env)" >&2; exit 1; }
  printf '%s' "$value"
}
DEMO_PASSWORD=$(env_value DEMO_PASSWORD)
AUTOMATION_CLIENT_SECRET=$(env_value AUTOMATION_CLIENT_SECRET)

echo "Creating demo logins in Keycloak"
docker compose exec -T -e DEMO_PASSWORD="$DEMO_PASSWORD" keycloak bash -s <<'KEYCLOAK'
set -euo pipefail
kc=/opt/keycloak/bin/kcadm.sh
$kc config credentials --server http://localhost:8080 --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" > /dev/null 2>&1
for entry in alice:Alice:responder bob:Bob:responder vera:Vera:viewer; do
  IFS=: read -r user name role <<< "$entry"
  if [ -z "$($kc get users -r pagerewind -q username="$user" -q exact=true --fields id --format csv --noquotes)" ]; then
    $kc create users -r pagerewind -s username="$user" -s enabled=true -s firstName="$name" -s lastName=Demo \
      -s email="$user@example.com" -s emailVerified=true > /dev/null 2>&1
  fi
  $kc set-password -r pagerewind --username "$user" --new-password "$DEMO_PASSWORD"
  $kc add-roles -r pagerewind --uusername "$user" --rolename "$role"
  echo "  $user ($role)"
done
KEYCLOAK

# The schedule API needs an admin token: the automation client has the admin role.
TOKEN=$(curl -fsS "$KEYCLOAK_URL/realms/pagerewind/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=pagerewind-automation \
  --data-urlencode "client_secret=$AUTOMATION_CLIENT_SECRET" | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

put() {
  curl -fsS -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data "$2" "$SCHEDULE_URL$1" > /dev/null
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
curl -fsS -H "Authorization: Bearer $TOKEN" "$SCHEDULE_URL/v1/teams/checkout-team/on-call"; echo
echo "Sign in at http://localhost:8080 as alice, bob or vera with DEMO_PASSWORD from .env."
