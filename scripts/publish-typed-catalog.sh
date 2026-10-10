#!/usr/bin/env bash
# Publish only an already validated catalog build from clean, immutable sources.
set -euo pipefail
publisher_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
app_root=$(cd "${3:-$publisher_root}" && pwd)
tools_root=$(cd "${1:-$app_root/../compose-ai-tools}" && pwd)
builder_root=$(cd "${2:-$app_root/../compose-ui-builder}" && pwd)
for source in "$publisher_root" "$app_root" "$tools_root" "$builder_root"; do
  git -C "$source" diff --quiet
  git -C "$source" diff --cached --quiet
done
source_sha=$(git -C "$app_root" rev-parse HEAD)
tools_sha=$(git -C "$tools_root" rev-parse HEAD)
builder_sha=$(git -C "$builder_root" rev-parse HEAD)
repo=${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}
tag="typed-catalog-preview-${source_sha}"
for dependency_sha in "$tools_sha" "$builder_sha"; do
  if ! rg -Fq "ref: $dependency_sha" "$app_root/.github/workflows/typed-catalog.yml"; then
    echo "Dependency revision differs from the validated source workflow" >&2
    exit 1
  fi
done
release_target=(--target "$source_sha")
if tag_sha=$(gh api "repos/$repo/git/ref/tags/$tag" --jq .object.sha 2>/dev/null); then
  if [[ "$tag_sha" != "$source_sha" ]]; then
    echo "Existing tag does not identify the validated source commit" >&2
    exit 1
  fi
  # An explicit commit target can require workflow permissions even for an existing tag.
  release_target=(--verify-tag)
fi
publication="$app_root/ui-builder-catalog/build/publication"
mkdir -p "$publication"
cp "$app_root/ui-builder-catalog/build/distributions/homeassistant-remotecompose-android-catalog.zip" "$publication/"
cp "$app_root/ui-builder-catalog/build/catalog/testDebugUnitTest/components.json" "$publication/"
cp "$app_root/ui-builder-catalog/build/catalog/testDebugUnitTest/ui-builder.json" "$publication/"
python3 - "$publication" "$repo" "$source_sha" "$tools_sha" "$builder_sha" <<'PYTHON'
import hashlib, json, pathlib, sys
out = pathlib.Path(sys.argv[1])
archive = 'homeassistant-remotecompose-android-catalog.zip'
files = [archive, 'components.json', 'ui-builder.json']
digests = {name: hashlib.sha256((out / name).read_bytes()).hexdigest() for name in files}
manifest = {
    'schema': 'compose-ui-builder-executable-bundle/v1-experimental',
    'catalogId': 'homeassistant-remotecompose', 'runtime': 'android', 'archive': archive,
    'source': {'repository': sys.argv[2], 'commit': sys.argv[3]},
    'toolsCommit': sys.argv[4], 'builderCommit': sys.argv[5], 'sha256': digests,
}
(out / 'bundle.json').write_text(json.dumps(manifest, indent=2) + '\n')
(out / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in digests.items()))
(out / 'catalog.json').write_text(json.dumps({
    'schema': 'design-parity-catalog/v1', 'system': 'homeassistant-remotecompose',
    'title': 'homeassistant-remotecompose typed catalog preview',
    'componentsFile': 'components.json', 'uiBuilderFile': 'ui-builder.json',
}, indent=2) + '\n')
PYTHON
notes="$publication/NOTES.md"
cat > "$notes" <<EOF
Experimental initial typed UI Builder catalog from $source_sha.

Includes the standard components.json/ui-builder.json pair and a trusted executable android bundle.
Source tools: $tools_sha. UI Builder: $builder_sha. Checksums and provenance are in bundle.json.
The host must supply its compatible runtime. This preview does not replace the production catalog.
EOF
# Never replace an existing generation; one source commit identifies one validated publication.
gh release create "$tag" "$publication/homeassistant-remotecompose-android-catalog.zip" "$publication/components.json" \
  "$publication/ui-builder.json" "$publication/catalog.json" "$publication/bundle.json" \
  "$publication/SHA256SUMS" --repo "$repo" "${release_target[@]}" \
  --title "Initial typed catalog preview (android)" --notes-file "$notes" --prerelease --latest=false
