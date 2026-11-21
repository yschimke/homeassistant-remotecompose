#!/usr/bin/env bash
# Copies a branch's Terrazzo integration and ESPHome component into this Home Assistant's
# /config (run in a terminal on the box: the SSH or Studio Code Server add-on), then checks and
# restarts Core. Usage: sync-to-ha.sh [branch] [--no-restart]
set -euo pipefail
branch="${1:-main}"
restart=1
[[ "${2:-}" == "--no-restart" ]] && restart=0
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL "https://codeload.github.com/yschimke/homeassistant-remotecompose/tar.gz/refs/heads/${branch}" \
  | tar xz -C "$work" --strip-components=1 \
      --wildcards '*/custom_components/terrazzo/*' '*/esphome/components/terrazzo_wall/*'
cp -r "$work/custom_components/terrazzo/." /config/custom_components/terrazzo/
mkdir -p /config/esphome/components/terrazzo_wall
cp -r "$work/esphome/components/terrazzo_wall/." /config/esphome/components/terrazzo_wall/
echo "synced ${branch}"
if (( restart )); then
  ha core check && ha core restart
fi
