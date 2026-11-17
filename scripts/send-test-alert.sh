#!/usr/bin/env bash
# Sends an Alertmanager-style alert to ingest.
#   ./scripts/send-test-alert.sh [firing|resolved] [fingerprint]
set -euo pipefail

INGEST_URL="${INGEST_URL:-http://localhost:8081}"
STATUS="${1:-firing}"
FINGERPRINT="${2:-demo-checkout-5xx}"
TOKEN="${INGEST_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f .env ]; then
  TOKEN=$(grep -E '^INGEST_TOKEN=' .env | cut -d= -f2-)
fi
if [ -z "$TOKEN" ]; then
  echo "Set INGEST_TOKEN (or run from the folder with .env)" >&2
  exit 1
fi

curl -fsS -X POST "$INGEST_URL/v1/alerts/alertmanager" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data @- <<JSON
{
  "version": "4",
  "status": "$STATUS",
  "receiver": "pagerewind",
  "commonLabels": {"alertname": "HighErrorRate"},
  "alerts": [{
    "status": "$STATUS",
    "labels": {"alertname": "HighErrorRate", "service": "checkout", "severity": "critical"},
    "annotations": {"summary": "Checkout 5xx rate above 5% for 5 minutes"},
    "startsAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
    "fingerprint": "$FINGERPRINT"
  }]
}
JSON
echo
