#!/usr/bin/env python3
"""Capture real Android app frames and verify the checked-in Play screenshot set."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / 'scripts/play-capture-config.json').read_text())
GRAPHICS = ROOT / CONFIG['graphics']
RECORDS = ROOT / 'docs/play-captures.json'

def command(*args):
    return subprocess.check_output(args, text=True).strip()

def check():
    records = json.loads(RECORDS.read_text())['captures']
    from PIL import Image
    found = set()
    for slot, minimum in CONFIG['slots'].items():
        files = sorted(p for p in (GRAPHICS / slot).iterdir() if p.suffix.lower() in {'.png', '.jpg', '.jpeg'})
        if not minimum <= len(files) <= 8:
            raise ValueError(f'{slot}: expected {minimum}–8 captures, found {len(files)}')
        for path in files:
            key = str(path.relative_to(GRAPHICS))
            found.add(key)
            record = records.get(key)
            if not record or record.get('source') != 'adb-screencap':
                raise ValueError(f'{key}: missing real-device capture provenance')
            if hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']:
                raise ValueError(f'{key}: image differs from its captured frame; recapture it')
            with Image.open(path) as image:
                w, h = image.size
                if min(w, h) < 320 or max(w, h) > 1800 or image.mode != 'RGB':
                    raise ValueError(f'{key}: invalid size/mode {image.size} {image.mode}')
                if slot == 'wear-screenshots' and w != h:
                    raise ValueError(f'{key}: Wear capture must be square')
                if slot == 'phone-screenshots' and abs(w / h - 9 / 16) > .005:
                    raise ValueError(f'{key}: phone capture must be 9:16')
                if [w, h] != record['dimensions']:
                    raise ValueError(f'{key}: dimensions differ from provenance')
    if set(records) != found:
        raise ValueError('Provenance contains missing or unconfigured screenshot files')
    print(f'Verified {len(found)} real app screenshots; branding assets are separate.')

def capture(args):
    from PIL import Image
    import io
    if not re.fullmatch(r'emulator-\d+', args.serial):
        raise ValueError('Use a disposable emulator; physical-device capture is deliberately disabled')
    if args.slot not in CONFIG['slots'] or not re.fullmatch(r'[a-zA-Z0-9_-]+\.png', args.name):
        raise ValueError('Choose a configured screenshot slot and a simple .png filename')
    adb = ['adb', '-s', args.serial]
    size = command(*adb, 'shell', 'wm', 'size')
    sizes = re.findall(r'(\d+)x(\d+)', size)
    if not sizes or max(map(int, sizes[-1])) > 1800:
        raise ValueError('Set the emulator display to at most 1800px on either side before capture')
    package = CONFIG['wearPackage'] if args.slot == 'wear-screenshots' else CONFIG['package']
    focus = command(*adb, 'shell', 'dumpsys', 'window')
    current = next((line for line in focus.splitlines() if 'mCurrentFocus=' in line), '')
    if f'{package}/' not in current:
        raise ValueError(f'{package} is not the foreground app; refusing to capture another app or system UI')
    raw = subprocess.check_output([*adb, 'exec-out', 'screencap', '-p'])
    image = Image.open(io.BytesIO(raw)).convert('RGB')
    if max(image.size) > 1800:
        raise ValueError('Captured display exceeds 1800px; nothing was written')
    path = GRAPHICS / args.slot / args.name
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    data = json.loads(RECORDS.read_text()) if RECORDS.exists() else {'captures': {}}
    data['captures'][str(path.relative_to(GRAPHICS))] = {
        'source': 'adb-screencap',
        'scenario': args.scenario,
        'package': package,
        'sourceRevision': command('git', '-C', str(ROOT), 'rev-parse', 'HEAD'),
        'capturedAt': datetime.now(timezone.utc).isoformat(),
        'device': command(*adb, 'shell', 'getprop', 'ro.product.model'),
        'sdk': command(*adb, 'shell', 'getprop', 'ro.build.version.sdk'),
        'dimensions': list(image.size),
        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
    }
    RECORDS.parent.mkdir(parents=True, exist_ok=True)
    RECORDS.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')
    print(path)

parser = argparse.ArgumentParser(description=__doc__)
sub = parser.add_subparsers(dest='action', required=True)
sub.add_parser('check')
p = sub.add_parser('capture')
p.add_argument('--serial', required=True)
p.add_argument('--slot', required=True)
p.add_argument('--name', required=True)
p.add_argument('--scenario', required=True, help='Describe the real app screen and any sample data')
args = parser.parse_args()
try:
    check() if args.action == 'check' else capture(args)
except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
    sys.exit(str(error))
