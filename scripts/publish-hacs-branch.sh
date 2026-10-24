#!/usr/bin/env bash
#
# Publish a tested terrazzo.zip as a HACS repository on the hacs/terrazzo
# branch of yschimke/homeassistant-remotecompose-out, tagged and released
# under the same vX.Y.Z as this repository's release.
#
#   scripts/publish-hacs-branch.sh v0.1.63 build/home-assistant/terrazzo.zip
#
# Why a separate repository: HACS downloads every github.com URL with
# aiohttp's 8190-byte header limit, and github.com now answers release
# assets and /archive/ zips with a ~9 KB content-security-policy header
# (hacs/integration#5643). Without zip_release, HACS falls back to
# fetching each file from raw.githubusercontent.com, which works — but
# only for committed files, and the built renderers (frontend/wasm,
# rc-player.js, ha-card.js) are not committed here. The -out repository
# already holds generated branches, so the built tree lives there.
#
# GH_TOKEN, when set, needs contents write on the -out repository
# (ARTIFACTS_TOKEN in CI); otherwise git and gh use local credentials.

set -euo pipefail

TAG="${1:?usage: $0 vX.Y.Z path/to/terrazzo.zip}"
ZIP="$(cd "$(dirname "${2:?usage: $0 vX.Y.Z path/to/terrazzo.zip}")" && pwd)/$(basename "$2")"
REPO="${HACS_REPOSITORY:-yschimke/homeassistant-remotecompose-out}"
BRANCH="${HACS_BRANCH:-hacs/terrazzo}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$(git -C "$ROOT" rev-parse "refs/tags/$TAG^{commit}" 2>/dev/null || git -C "$ROOT" rev-parse HEAD)"

[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "A stable vX.Y.Z tag is required, got $TAG"; exit 2; }
[[ -f "$ZIP" ]] || { echo "missing $ZIP"; exit 2; }

if [[ -n "${GH_TOKEN:-}" ]]; then
  URL="https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
else
  URL="https://github.com/${REPO}.git"
fi

if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  echo "$REPO already has release $TAG; nothing to publish"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
git init -q
git remote add origin "$URL"
# A previous run pushed the tag but failed before its release: finish that one.
if git ls-remote --exit-code --tags origin "refs/tags/${TAG}" >/dev/null; then
  echo "$REPO already has tag $TAG without a release; creating the release"
  RESUME=1
# Fetch only this branch: the -out repository's other branches are large.
elif git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null; then
  git fetch -q --depth 1 origin "$BRANCH"
  git switch -q -c "$BRANCH" FETCH_HEAD
  git rm -rq --ignore-unmatch .
else
  git switch -q --orphan "$BRANCH"
fi

if [[ -z "${RESUME:-}" ]]; then
mkdir -p custom_components/terrazzo
unzip -q "$ZIP" -d custom_components/terrazzo
grep -q "\"version\": \"${TAG#v}\"" custom_components/terrazzo/manifest.json \
  || { echo "$ZIP is not ${TAG}: manifest.json version differs"; exit 1; }

# Same manifest as the source repository's, minus the release-asset download.
python3 - "$ROOT/hacs.json" > hacs.json <<'EOF'
import json, sys
manifest = json.load(open(sys.argv[1]))
for key in ("zip_release", "filename"):
    manifest.pop(key, None)
print(json.dumps(manifest, indent=2))
EOF

cat > README.md <<EOF
# Terrazzo Remote Compose — HACS distribution

Built release of the Terrazzo Home Assistant integration from
[yschimke/homeassistant-remotecompose](https://github.com/yschimke/homeassistant-remotecompose).
Each \`vX.Y.Z\` release here is generated from the same-numbered release there.

Add \`https://github.com/${REPO}\` to HACS as a custom **Integration**
repository. Installation, updates and rollback are documented in
[docs/HOME_ASSISTANT.md](https://github.com/yschimke/homeassistant-remotecompose/blob/main/docs/HOME_ASSISTANT.md).
EOF

git add -A
git -c user.name="${GIT_AUTHOR_NAME:-Yuri Schimke}" -c user.email="${GIT_AUTHOR_EMAIL:-yuri@schimke.ee}" \
  commit -q -m "chore(hacs): publish terrazzo ${TAG}" \
  -m "Source: yschimke/homeassistant-remotecompose@${SOURCE}"
git tag "$TAG"
git push -q origin "HEAD:refs/heads/${BRANCH}" "refs/tags/${TAG}"
fi

gh release create "$TAG" -R "$REPO" --verify-tag --latest \
  --title "Terrazzo ${TAG#v}" \
  --notes "HACS build of [yschimke/homeassistant-remotecompose ${TAG}](https://github.com/yschimke/homeassistant-remotecompose/releases/tag/${TAG})."
echo "Published $REPO $TAG on $BRANCH"
