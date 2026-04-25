#!/usr/bin/env sh
# Optional entrypoint for builds that pull in HA's bashio base instead of
# distroless. Reads /data/options.json (the resolved add-on options) and
# exports them as env vars before exec'ing the JVM.
#
# Distroless build (the default) bypasses this and goes straight to the
# JVM through the Dockerfile's ENTRYPOINT.
set -eu

if [ -f /data/options.json ]; then
  LOG_LEVEL=$(jq -r '.log_level // "info"' /data/options.json)
  export LOG_LEVEL
fi

exec /app/bin/addon-server
