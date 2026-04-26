#!/usr/bin/env bash
# Wire the repo's .githooks/ directory so the ktfmt pre-commit check runs
# locally on every commit. Mirrors the `./gradlew installGitHooks` task —
# done here directly to avoid a Gradle warm-up on session start.
set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

git config core.hooksPath .githooks
echo "Configured git core.hooksPath -> .githooks"
