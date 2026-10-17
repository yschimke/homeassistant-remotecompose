#!/usr/bin/env python3
"""Package the local HA integration with built WASM and TS-player assets."""
import argparse
from pathlib import Path
import shutil
import tempfile
import zipfile


def package(wasm: Path, embed: Path, output: Path):
    for asset in (wasm / 'index.html', wasm / 'terrazzo.js', embed / 'ha-card.js', embed / 'rc-player.js'):
        if not asset.is_file():
            raise FileNotFoundError(f'Build the web assets first: missing {asset}')
    if not any(wasm.rglob('*.wasm')):
        raise FileNotFoundError('No compiled WASM in the app distribution')
    source = Path(__file__).resolve().parents[1] / 'home-assistant/custom_components/terrazzo'
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        component = root / 'custom_components/terrazzo'
        shutil.copytree(source, component, ignore=shutil.ignore_patterns('__pycache__'))
        shutil.copytree(wasm, component / 'frontend/wasm')
        shutil.copytree(embed, component / 'frontend/embed')
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(root.rglob('*')):
                if path.is_file():
                    archive.write(path, path.relative_to(root))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wasm', type=Path, required=True)
    parser.add_argument('--embed', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package(args.wasm, args.embed, args.output)
