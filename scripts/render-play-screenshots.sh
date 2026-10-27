#!/usr/bin/env bash
# Validate real app captures. This entrypoint intentionally never renders previews.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/play-captures.py check
echo 'To refresh screenshots, capture the running app with scripts/play-captures.py capture; see docs/play-screenshots.md.'
