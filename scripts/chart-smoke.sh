#!/usr/bin/env bash
# Checks a fresh "helm install" of charts/pagerewind with the default values (bundled data stores and
# Keycloak). Run after "helm install --wait"; reaches the services through kubectl port-forward.
#   scripts/chart-smoke.sh [release] [namespace]
set -euo pipefail

release="${1:-pagerewind}"
namespace="${2:-pagerewind}"
issuer="http://auth.localtest.me/realms/pagerewind"
k() { kubectl -n "$namespace" "$@"; }

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; k get pods >&2 || true; exit 1; }
retry() {
  local tries=$1; shift
  for _ in $(seq "$tries"); do "$@" > /dev/null 2>&1 && return 0; sleep 2; done
  return 1
}
secret() { k get secret "$release-secrets" -o jsonpath="{.data.$1}" | base64 -d; }

pids=()
cleanup() { for pid in "${pids[@]}"; do kill "$pid" 2> /dev/null || true; done; }
trap cleanup EXIT
forward() {
  k port-forward "svc/$release-$1" "$2:$3" > /dev/null 2>&1 &
  pids+=($!)
}
forward gateway 18080 80
forward ingest 18081 80
forward status 18085 80
forward keycloak 18180 8080
up() { curl -fsS "http://localhost:$1/$2" > /dev/null; }
retry 30 up 18080 actuator/health/readiness || fail "gateway not reachable"
retry 30 up 18081 actuator/health/readiness || fail "ingest not reachable"
retry 30 up 18085 actuator/health/readiness || fail "status not reachable"
retry 30 up 18180 realms/pagerewind || fail "keycloak not reachable"
pass "gateway, ingest, status and Keycloak answer"

# Keycloak imported the realm and names itself by the public address.
discovered=$(curl -fsS "http://localhost:18180/realms/pagerewind/.well-known/openid-configuration" | jq -r .issuer)
[ "$discovered" = "$issuer" ] || fail "issuer is $discovered, expected $issuer"
pass "realm imported, issuer $issuer"

# An alert through ingest becomes an incident and shows on the status page.
INGEST_URL=http://localhost:18081 INGEST_TOKEN="$(secret ingest-token)" \
  "$(dirname "$0")/send-test-alert.sh" firing "chart-smoke-$(date +%s)" > /dev/null
outage() { [ "$(curl -fsS http://localhost:18085/v1/status | jq -r '.services[] | select(.service == "checkout") | .level')" = MAJOR_OUTAGE ]; }
retry 60 outage || fail "status page did not show checkout as a major outage"
pass "alert -> incident -> status page shows checkout as a major outage"

# A token from the bundled Keycloak is accepted by the API behind the gateway.
token=$(curl -fsS "http://localhost:18180/realms/pagerewind/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=pagerewind-automation \
  --data-urlencode "client_secret=$(secret automation-client-secret)" | jq -r .access_token)
incidents=$(curl -fsS -H "Authorization: Bearer $token" "http://localhost:18080/api/v1/incidents?limit=50")
[ "$(echo "$incidents" | jq '[.[] | select(.service == "checkout")] | length')" -ge 1 ] || fail "API did not list the incident: $incidents"
[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:18080/api/v1/incidents")" = 401 ] || fail "API without a token must answer 401"
pass "API through the gateway: 200 with a Keycloak token, 401 without"

# A browser is sent to the Keycloak login page at the public address.
first=$(curl -s -o /dev/null -w '%{redirect_url}' -H 'Accept: text/html' "http://localhost:18080/ui/incidents")
second=$(curl -s -o /dev/null -w '%{redirect_url}' -H 'Accept: text/html' -c /dev/null "$first")
case "$second" in
  "$issuer/protocol/openid-connect/auth?"*) pass "web UI redirects to the Keycloak login" ;;
  *) fail "login redirect went to '$second' (first hop '$first')" ;;
esac
