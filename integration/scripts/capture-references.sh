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

echo "==> running puppeteer capture"
docker compose --profile capture run --rm puppeteer

echo "==> tearing down"
docker compose down

echo "==> done. references under ../references/"
