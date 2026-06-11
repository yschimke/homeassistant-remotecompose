#!/usr/bin/env bash
# Cloud session bootstrap for Compose @Preview rendering.
#
# Provisions the toolchain + the compose-preview skill via coo.ee (devenv
# backend), then bootstraps the compose-preview CLI and warms preview
# discovery once. The warm-up resolves the auto-injected Gradle plugin's
# artifacts so the first interactive `compose-preview list` works WITHOUT
# `ee.schimke.composeai.preview` being applied in any build file.
#
# No-op on developer machines (gated on CLAUDE_CODE_REMOTE), matching the
# sibling session-start.sh hook.
set -euo pipefail

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

# 1. Toolchain (JDK adopted; Android SDK + devenv via Nix) + the
#    compose-preview skill bundle. Idempotent: re-runs short-circuit.
curl -fsSL 'https://env.coo.ee/compose?devenv=1' | bash

# 2. Activate the env coo.ee just persisted into this and future shells.
[ -f "$HOME/.config/coo-ee/env.sh" ] && . "$HOME/.config/coo-ee/env.sh" || true
export PATH="$HOME/.local/bin:$PATH"

# 3. Bootstrap the compose-preview CLI (the skill ships a self-installing
#    stub) and warm discovery once from the project root. Best-effort: a
#    network/build hiccup here must not fail session start.
cli=""
if command -v compose-preview >/dev/null 2>&1; then
  cli=compose-preview
elif [ -x "$HOME/.claude/skills/compose-preview/scripts/compose-preview" ]; then
  cli="$HOME/.claude/skills/compose-preview/scripts/compose-preview"
fi
[ -n "$cli" ] && { "$cli" list >/dev/null 2>&1 || true; }
