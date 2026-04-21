#!/usr/bin/env bash
#
# Puppeteer writes reference PNGs as uid 10042 (pptruser). Chown back
# to the host user so git operations work. Uses a throwaway alpine
# container since the files are root-in-container-owned.

set -euo pipefail
cd "$(dirname "$0")/.."

HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

docker run --rm -v "$PWD/references:/r" alpine sh -c \
    "chown -R ${HOST_UID}:${HOST_GID} /r && chmod -R u=rwX,g=rX,o=rX /r"
echo "ownership restored on $(pwd)/references"
