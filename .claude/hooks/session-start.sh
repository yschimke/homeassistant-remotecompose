#!/usr/bin/env bash
# Wire the repo's .githooks/ directory so the ktfmt pre-commit check runs
# locally on every commit. Mirrors the `./gradlew installGitHooks` task —
# done here directly to avoid a Gradle warm-up on session start.
set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

git config core.hooksPath .githooks
echo "Configured git core.hooksPath -> .githooks"

# Install Build Brief. Best-effort — the network may be unavailable under the
# session's network policy, so a failure here must not abort session start.
echo "installing Build Brief"
# Linux/macOS — downloads the matching release tarball from GitHub.
if curl -fsSL https://bb.staticvar.dev/install.sh | bash; then
  echo "Build Brief installed"
else
  echo "Build Brief install failed (non-fatal); continuing" >&2
fi
