#!/usr/bin/env python3
"""Bundle the existing WASM app and TS embed as a manually installable HA integration."""
import argparse
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def package(wasm, player, output):
    for path in (wasm / "index.html", wasm / "terrazzo.js", player):
        if not path.is_file():
            raise FileNotFoundError(f"Build asset missing: {path}")
    output.mkdir(parents=True, exist_ok=True)
    stage = output / "custom_components" / "terrazzo"
    if stage.exists():
        shutil.rmtree(stage)
    shutil.copytree(ROOT / "home-assistant/custom_components/terrazzo", stage, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copytree(wasm, stage / "frontend/wasm", dirs_exist_ok=True)
    shutil.copyfile(ROOT / "embed/ha-card.js", stage / "frontend/ha-card.js")
    shutil.copyfile(player, stage / "frontend/rc-player.js")
    archive = output / "terrazzo-home-assistant.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        for path in sorted(stage.rglob('*')):
            if path.is_file():
                bundle.write(path, path.relative_to(output))
    return archive


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wasm', type=Path, default=ROOT / 'app-ui/build/dist/wasmJs/productionExecutable')
    parser.add_argument('--player', type=Path, default=ROOT / 'design-server/build/generated/embed/embed/v1/rc-player.js')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/home-assistant')
    args = parser.parse_args()
    print(package(args.wasm, args.player, args.output))
