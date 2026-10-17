#!/usr/bin/env bash
# Run on the deployment host. All desired service configuration lives in compose.yaml.
set -euo pipefail
umask 077

if (( $# )); then
  echo 'Usage: bash deploy/demo-ha/provision.sh' >&2
  exit 2
fi

demo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# Pin both the file and project: the host may export settings for its preview stack.
compose=(docker compose --project-directory "$demo_dir" --file "$demo_dir/compose.yaml" --project-name public-demo-ha)

docker compose version >/dev/null
docker info >/dev/null
"${compose[@]}" config --quiet
"${compose[@]}" --profile setup --profile verify build gateway bootstrap verify
"${compose[@]}" up -d homeassistant
"${compose[@]}" --profile setup run --rm bootstrap
"${compose[@]}" up -d gateway
"${compose[@]}" --profile verify run --rm verify

echo 'Backend provisioned; isolated-edge reads, rejected writes and live updates verified.'
echo 'Next: enable the committed Caddy overlay, then run the public HTTPS verifier (see README.md).'
echo 'Public rollout is not verified by this script. Private credentials remain on this host.'
