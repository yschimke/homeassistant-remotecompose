#!/usr/bin/env bash
# Fail the build when an AUTHORED preview fails to render.
#
# Why this exists: `compose-preview.yml` runs the render with
# `missing-renders: warn`, because one preview in this repo can never render and
# nobody wrote it. The plugin synthesises a preview per `<activity>` in the
# merged AndroidManifest (the launcher activity's render is meant to be the
# app's hero image), and the renderer deliberately pins a stub
# `android.app.Application` so previews don't run app-lifecycle init — so
# `MainActivity.onCreate`, which reads its DI graph via
# `application as TerrazzoApplication`, throws ClassCastException. The other
# `activity__*` entries are the same story, two of them for third-party classes
# (`net.openid.appauth.*`) merged in from a dependency, which this repo could not
# fix even in principle.
#
# `warn` on its own would also swallow a REAL regression — exactly how
# `WearClockSmall` sat broken and unnoticed. So the policy is narrowed here
# rather than left open: a synthetic `activity__…` render may fail; anything
# anyone actually wrote may not.
#
# The check reads the renderer's own `.error.json` sidecars rather than
# re-deriving "which previews should have produced a file", so it can't drift
# from the plugin's accounting the way a re-implementation would.
#
# Usage: check-render-errors.sh [root]   (root defaults to the working directory)
set -euo pipefail

root="${1:-.}"

# Synthetic, tool-generated previews that are allowed to fail. Keep this as
# narrow as it can be — it is an allowlist of things nobody authored, not a
# place to silence real breakage.
allow_re='^activity__'

mapfile -t sidecars < <(
  find "$root" -path '*/build/compose-previews/renders/*.error.json' -print 2>/dev/null | sort
)

if [ "${#sidecars[@]}" -eq 0 ]; then
  echo "check-render-errors: no render-error sidecars found."
  exit 0
fi

fatal=()
tolerated=()
for f in "${sidecars[@]}"; do
  stem="$(basename "$f")"
  stem="${stem%.png.error.json}"
  stem="${stem%.error.json}"
  if [[ "$stem" =~ $allow_re ]]; then
    tolerated+=("$stem")
  else
    fatal+=("$f")
  fi
done

if [ "${#tolerated[@]}" -gt 0 ]; then
  echo "check-render-errors: tolerated ${#tolerated[@]} synthetic activity preview(s):"
  printf '  - %s\n' "${tolerated[@]}"
fi

if [ "${#fatal[@]}" -eq 0 ]; then
  echo "check-render-errors: no authored preview failed to render."
  exit 0
fi

echo
echo "check-render-errors: ${#fatal[@]} authored preview(s) failed to render." >&2
for f in "${fatal[@]}"; do
  stem="$(basename "$f")"
  stem="${stem%.png.error.json}"
  module="$(printf '%s' "$f" | sed -E 's|^.*/([^/]+)/build/compose-previews/renders/.*$|\1|')"
  # Pull the headline fields out of the sidecar without assuming jq is present.
  detail="$(python3 - "$f" <<'PY' 2>/dev/null || echo "(could not parse sidecar)"
import json, sys

try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print("(unreadable sidecar: %s)" % e)
    raise SystemExit

exc = d.get("exception", "?").rsplit(".", 1)[-1]
msg = (d.get("message") or "").strip()
frame = d.get("topAppFrame") or {}
where = ""
if frame.get("file"):
    where = " (at %s:%s)" % (frame["file"], frame.get("line", 0))
print("%s: %s%s" % (exc, msg, where) if msg else "%s%s" % (exc, where))
PY
  )"
  echo "  - [$module] $stem" >&2
  echo "      $detail" >&2
done
echo >&2
echo "A @Preview must be callable with no arguments — a default parameter compiles to a" >&2
echo "\$default-mask signature and the renderer's getDeclaredComposableMethod lookup fails." >&2
echo "Full stack traces are in the composePreviewRender-reports artifact on this run." >&2
exit 1
