#!/usr/bin/env bash
set -euo pipefail
umask 077
if (( $# )); then
  echo 'Usage: bash deploy/demo-ha/enable-native.sh' >&2
  exit 2
fi
demo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
compose=(docker compose --project-directory "$demo_dir" --file "$demo_dir/compose.yaml" --file "$demo_dir/compose.native.yaml" --project-name public-demo-ha)
"${compose[@]}" config --quiet
"${compose[@]}" build native
# Auth and onboarding storage must have a single writer. Restart HA even if setup fails.
"${compose[@]}" stop homeassistant
trap '"${compose[@]}" up -d homeassistant' EXIT
"${compose[@]}" --profile native-setup run --rm native-setup
"${compose[@]}" up -d homeassistant native
trap - EXIT
echo 'Native read-only service started. Public routing and HTTPS verification remain (see README).'
