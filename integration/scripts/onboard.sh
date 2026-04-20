#!/usr/bin/env bash
#
# Automate HA first-run onboarding via its public REST API. Produces
# integration/.env.local with a fresh access token. Idempotent: if the
# server is already past user creation (step user done), exits cleanly.
#
# Usage:
#   HA_HOST_PORT=8124 scripts/onboard.sh

set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${HA_HOST_PORT:-8124}"
BASE="http://127.0.0.1:${PORT}"
CLIENT_ID="${BASE}"

USER="admin"
PASS="admin"
NAME="Admin"

status=$(curl -fsS "${BASE}/api/onboarding" | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d[0]["done"])')
if [[ "$status" == "True" ]]; then
  echo "onboarding already complete; keeping existing .env.local"
  exit 0
fi

echo "==> creating owner user"
resp=$(curl -fsS -X POST "${BASE}/api/onboarding/users" \
  -H 'Content-Type: application/json' \
  --data "$(python3 -c "
import json
print(json.dumps({
  'client_id': '${CLIENT_ID}',
  'name': '${NAME}',
  'username': '${USER}',
  'password': '${PASS}',
  'language': 'en',
}))")")
AUTH_CODE=$(echo "$resp" | python3 -c 'import json,sys;print(json.load(sys.stdin)["auth_code"])')

echo "==> exchanging auth code for access token"
tok=$(curl -fsS -X POST "${BASE}/auth/token" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "code=${AUTH_CODE}" \
  --data-urlencode "grant_type=authorization_code")
ACCESS=$(echo "$tok" | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')
REFRESH=$(echo "$tok" | python3 -c 'import json,sys;print(json.load(sys.stdin)["refresh_token"])')

AUTH="Authorization: Bearer ${ACCESS}"

# Persist token before the last few onboarding steps so a late failure
# doesn't cost us the access token.
cat > .env.local <<EOF
HA_HOST_PORT=${PORT}
HA_TOKEN=${ACCESS}
HA_REFRESH_TOKEN=${REFRESH}
EOF
chmod 600 .env.local

echo "==> posting core_config"
curl -fsS -X POST "${BASE}/api/onboarding/core_config" \
  -H "$AUTH" -H 'Content-Type: application/json' --data '{}' >/dev/null

echo "==> posting analytics"
curl -fsS -X POST "${BASE}/api/onboarding/analytics" \
  -H "$AUTH" -H 'Content-Type: application/json' --data '{}' >/dev/null

echo "==> posting integration (best-effort; not required for frontend access)"
curl -fsS -X POST "${BASE}/api/onboarding/integration" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  --data "{\"client_id\": \"${CLIENT_ID}\", \"redirect_uri\": \"${CLIENT_ID}\"}" \
  >/dev/null || echo "   (integration step skipped — HA still fully usable)"

echo "==> onboarding complete, token saved to integration/.env.local"
