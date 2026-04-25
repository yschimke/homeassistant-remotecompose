#!/usr/bin/env bash
#
# End-to-end smoke test: build the add-on Docker image, run it next to a
# real Home Assistant container, hit the public endpoints, tear down.
#
# Prereqs:
#   - docker + docker compose v2
#   - integration/.env.local with HA_TOKEN=<long-lived access token>
#     (created via the existing reference-capture onboarding flow — see
#     integration/scripts/capture-references.sh)
#   - jq
#
# Exit codes: 0 success, non-zero on any failed check.

set -euo pipefail

cd "$(dirname "$0")"

# --- preflight ---------------------------------------------------------

ENV_FILE="../../integration/.env.local"
if [[ ! -f "$ENV_FILE" ]]; then
  cat >&2 <<'EOF'
Missing integration/.env.local — first-time setup needed.

The add-on container needs a Home Assistant access token. Create one
once via the existing onboarding flow:

  1) cd integration
  2) docker compose up -d homeassistant
  3) Open http://localhost:${HA_HOST_PORT:-8124} and complete onboarding
  4) Profile → Security → create a long-lived access token
  5) Put it in integration/.env.local as HA_TOKEN=<...>
  6) docker compose down

Then re-run this script.
EOF
  exit 2
fi

# Load the token so the smoke checks below can also read it (the compose
# `env_file` only injects it into the addon-server container).
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

if [[ -z "${HA_TOKEN:-}" ]]; then
  echo "HA_TOKEN missing in $ENV_FILE" >&2
  exit 2
fi

for cmd in docker jq curl; do
  command -v "$cmd" >/dev/null || { echo "missing: $cmd" >&2; exit 2; }
done

# --- lifecycle ---------------------------------------------------------

# Project name keeps containers separate from any other docker-compose
# stack running on the host (e.g. integration/compose.yaml on the same
# user's box).
COMPOSE=(docker compose -p ha-rc-addon-test -f compose.yaml)

cleanup() {
  echo "==> tearing down"
  "${COMPOSE[@]}" logs addon-server > /tmp/ha-rc-addon-test-server.log 2>&1 || true
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> building + starting (HA + addon-server)"
"${COMPOSE[@]}" up -d --build

# --- wait for /readyz --------------------------------------------------

ADDON_URL="http://127.0.0.1:18099"
echo "==> waiting for ${ADDON_URL}/readyz (HA hydrate may take ~30s)"
ready=""
for _ in $(seq 1 90); do
  status=$(curl -s -o /dev/null -w "%{http_code}" "${ADDON_URL}/readyz" || echo "000")
  if [[ "$status" == "200" ]]; then
    ready=1
    break
  fi
  sleep 1
done
if [[ -z "$ready" ]]; then
  echo "FAIL: /readyz never returned 200" >&2
  echo "---- addon-server logs ----" >&2
  "${COMPOSE[@]}" logs addon-server >&2 || true
  exit 1
fi
echo "==> ready"

# --- endpoint checks ---------------------------------------------------

echo "==> /healthz"
curl -fsS "${ADDON_URL}/healthz" | grep -qx "ok"

echo "==> /v1/snapshot has entities"
snapshot=$(curl -fsS "${ADDON_URL}/v1/snapshot")
count=$(jq '.states | length' <<<"$snapshot")
if (( count <= 0 )); then
  echo "FAIL: /v1/snapshot returned 0 entities" >&2
  echo "$snapshot" | head -c 500 >&2
  exit 1
fi
echo "    $count entities"

echo "==> /v1/dashboards is a JSON array"
curl -fsS "${ADDON_URL}/v1/dashboards" | jq -e 'type == "array"' >/dev/null

echo "==> /v1/cards/x.rc returns the typed 501 placeholder"
status=$(curl -s -o /dev/null -w "%{http_code}" "${ADDON_URL}/v1/cards/whatever.rc")
if [[ "$status" != "501" ]]; then
  echo "FAIL: expected 501 for unimplemented .rc endpoint, got $status" >&2
  exit 1
fi

echo "==> all checks passed"
