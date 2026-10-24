#!/usr/bin/env bash
#
# One-shot: bring up the reference HA, run puppeteer to crop each card
# to a committed PNG, tear down.
#
# First run: `scripts/onboard.sh` completes onboarding through HA's API and writes .env.local
# (HA_HOST_PORT, HA_TOKEN). This script runs it, with HA up, when .env.local is missing, so a
# fresh checkout captures with no manual steps. To capture with a long-lived token instead, create
# one under Profile → Security and put it in .env.local as HA_TOKEN=<...>.
#
# The container's user writes into ../references, so that directory must be writable by it.

set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env.local ]]; then
  docker compose up -d homeassistant
  until [[ "$(docker inspect -f '{{.State.Health.Status}}' ha-remotecompose-ref 2>/dev/null)" == "healthy" ]]; do
    sleep 2
  done
  scripts/onboard.sh
fi

# Load HA_HOST_PORT (and anything else) so `docker compose` sees it.
set -a
# shellcheck disable=SC1091
source .env.local
set +a

echo "==> bringing up HA"
docker compose up -d homeassistant

echo "==> waiting for healthcheck"
until [[ "$(docker inspect -f '{{.State.Health.Status}}' ha-remotecompose-ref 2>/dev/null)" == "healthy" ]]; do
  sleep 2
done

# onboard.sh's access token lasts about 30 minutes. A later capture reuses .env.local, so rotate it
# from the stored refresh token now that HA is up; a hand-made long-lived token has none and stays.
if grep -q '^HA_REFRESH_TOKEN=.' .env.local; then
  ../scripts/refresh-ha-token.sh
  set -a
  # shellcheck disable=SC1091
  source .env.local
  set +a
fi

# The todo-list reference's items (previews Fixtures.shoppingList). They live in HA's shopping list
# store rather than in configuration.yaml, so reset them to the fixture's on every capture: two
# active items, then one completed.
echo "==> seeding the shopping list"
HA_BASE="http://127.0.0.1:${HA_HOST_PORT:-8124}"
ha_service() {
  curl -fsS -X POST "${HA_BASE}/api/services/$1" -H "Authorization: Bearer ${HA_TOKEN}" \
    -H 'Content-Type: application/json' --data "$2"
}
# The healthcheck passes once the frontend answers, which can be before the shopping list loads.
for _ in $(seq 60); do
  curl -fs -o /dev/null "${HA_BASE}/api/states/todo.shopping_list" \
    -H "Authorization: Bearer ${HA_TOKEN}" && break
  sleep 1
done
existing=$(ha_service 'todo/get_items?return_response' '{"entity_id": "todo.shopping_list"}' \
  | python3 -c 'import json,sys;print(json.dumps([i["uid"] for i in json.load(sys.stdin)["service_response"]["todo.shopping_list"]["items"]]))')
if [[ "$existing" != "[]" ]]; then
  ha_service todo/remove_item "{\"entity_id\": \"todo.shopping_list\", \"item\": ${existing}}" >/dev/null
fi
for item in Salt Butter Milk; do
  ha_service todo/add_item "{\"entity_id\": \"todo.shopping_list\", \"item\": \"${item}\"}" >/dev/null
done
ha_service todo/update_item \
  '{"entity_id": "todo.shopping_list", "item": "Milk", "status": "completed"}' >/dev/null

echo "==> running puppeteer capture"
docker compose --profile capture run --rm puppeteer

echo "==> tearing down"
docker compose down

echo "==> done. references under ../references/"
