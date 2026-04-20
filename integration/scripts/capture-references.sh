#!/usr/bin/env bash
#
# One-shot: bring up the reference HA, run puppeteer to crop each card
# to a committed PNG, tear down.
#
# First-run setup:
#   1. cp .env.example .env.local, set HA_HOST_PORT (default 8124)
#   2. docker compose up -d homeassistant
#   3. Open http://localhost:${HA_HOST_PORT} and complete onboarding.
#   4. Profile → Security → Create a long-lived access token.
#   5. Paste the token into .env.local as HA_TOKEN=<...>
#   6. docker compose down   (config persists in integration/config/)
#
# After that, rerunning this script is fully automated.

set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env.local ]]; then
  cat >&2 <<EOF
Missing integration/.env.local. Run first-time setup:

  1) cp .env.example .env.local   (edit HA_HOST_PORT if 8124 is taken)
  2) docker compose up -d homeassistant
  3) Open http://localhost:\${HA_HOST_PORT:-8124}, complete onboarding
  4) Create a long-lived access token (Profile → Security)
  5) Put the token in integration/.env.local as HA_TOKEN=<...>
  6) docker compose down
  7) Re-run this script.
EOF
  exit 2
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

echo "==> running puppeteer capture"
docker compose --profile capture run --rm puppeteer

echo "==> tearing down"
docker compose down

echo "==> done. references under ../references/"
