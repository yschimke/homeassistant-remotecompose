#!/usr/bin/env bash
#
# Rotate the HA access token in integration/.env.local using the
# stored HA_REFRESH_TOKEN. HA's access tokens TTL out after ~30 min;
# the refresh token is long-lived.

set -euo pipefail
cd "$(dirname "$0")/../integration"

[[ -f .env.local ]] || { echo "no integration/.env.local — run scripts/onboard.sh first"; exit 2; }

PORT=$(grep '^HA_HOST_PORT=' .env.local | cut -d= -f2)
PORT="${PORT:-8124}"
REFRESH=$(grep '^HA_REFRESH_TOKEN=' .env.local | cut -d= -f2)
[[ -n "$REFRESH" ]] || { echo "no HA_REFRESH_TOKEN in .env.local"; exit 2; }

NEW=$(curl -fsS -X POST "http://127.0.0.1:${PORT}/auth/token" \
    --data-urlencode "client_id=http://127.0.0.1:${PORT}" \
    --data-urlencode "refresh_token=${REFRESH}" \
    --data-urlencode "grant_type=refresh_token" \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['access_token'])")

sed -i "s|^HA_TOKEN=.*|HA_TOKEN=${NEW}|" .env.local
echo "HA_TOKEN refreshed"
