#!/usr/bin/env python3
"""Bundle the same WASM/TS renderers for manual and HACS release installation."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def package(wasm, player, output, version=None):
    manifest = json.loads((ROOT / "custom_components/terrazzo/manifest.json").read_text())
    if version is not None:
        version = version.removeprefix("v")
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
            raise ValueError("Release version must be a stable semantic version")
        if version != manifest["version"]:
            raise ValueError("Release tag must match the integration manifest version")
    for path in (wasm / "index.html", wasm / "terrazzo.js", player):
        if not path.is_file():
            raise FileNotFoundError(f"Build asset missing: {path}")
    output.mkdir(parents=True, exist_ok=True)
    stage = output / "custom_components" / "terrazzo"
    if stage.exists():
        shutil.rmtree(stage)
    shutil.copytree(ROOT / "custom_components/terrazzo", stage, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copytree(wasm, stage / "frontend/wasm", dirs_exist_ok=True)
    shutil.copyfile(ROOT / "embed/ha-card.js", stage / "frontend/ha-card.js")
    shutil.copyfile(player, stage / "frontend/rc-player.js")
    archive = output / "terrazzo-home-assistant.zip"
    hacs_archive = output / "terrazzo.zip"
    # HACS extracts directly into /config/custom_components/terrazzo; manual
    # installs extract into /config. Both archives contain the same payload.
    for target, relative_to in ((archive, output), (hacs_archive, stage)):
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as bundle:
            for path in sorted(stage.rglob('*')):
                if path.is_file():
                    bundle.write(path, path.relative_to(relative_to))
    (output / "SHA256SUMS-home-assistant.txt").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
        for path in (archive, hacs_archive)
    ))
    return archive


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wasm', type=Path, default=ROOT / 'app-ui/build/dist/wasmJs/productionExecutable')
    parser.add_argument('--player', type=Path, default=ROOT / 'design-server/build/generated/embed/embed/v1/rc-player.js')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/home-assistant')
    parser.add_argument('--version', help='Require this release tag to match the source manifest')
    args = parser.parse_args()
    print(package(args.wasm, args.player, args.output, args.version))
