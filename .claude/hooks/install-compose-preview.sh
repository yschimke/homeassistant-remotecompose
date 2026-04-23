#!/usr/bin/env bash
#
# SessionStart hook: drops the compose-preview CLI on $PATH and sets
# JAVA_HOME to the JDK 17 the project's Gradle toolchain pins to. Lifted
# verbatim from compose-ai-tools PR #159 (§2 "Trusted mode quickstart").
#
# Idempotent: only downloads on first run; later sessions reuse the
# cached tarball under ~/.local/share/compose-preview.
#
# Requires the environment setup script to have installed
# openjdk-17-jdk-headless. For Android-consumer builds (this repo)
# also flip the session's network level to Custom + add dl.google.com
# and maven.google.com to the allowlist — Trusted alone can't resolve
# AGP.
set -euo pipefail

VER=0.7.7 # bump when a new release ships
TARGET="$HOME/.local/share/compose-preview"
BIN="$TARGET/compose-preview-$VER/bin/compose-preview"

if [[ ! -x "$BIN" ]]; then
  mkdir -p "$TARGET"
  curl -fsSL -o /tmp/compose-preview.tar.gz \
    "https://github.com/yschimke/compose-ai-tools/releases/download/v${VER}/compose-preview-${VER}.tar.gz"
  tar -xzf /tmp/compose-preview.tar.gz -C "$TARGET"
fi

echo "PATH=$(dirname "$BIN"):$PATH" >> "$CLAUDE_ENV_FILE"
echo "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64" >> "$CLAUDE_ENV_FILE"
