#!/usr/bin/env python3
"""Name native installers by app version, OS and architecture, and checksum them."""
import hashlib
import os
import platform
import sys
from pathlib import Path
from shutil import copyfile

module, app, version = sys.argv[1:]
os_name = os.environ.get("RUNNER_OS", platform.system()).lower()
arch = os.environ.get("RUNNER_ARCH", platform.machine()).lower()
arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "arm64"}.get(arch, arch)
output = Path("_release-desktop")
output.mkdir(exist_ok=True)
installers = sorted(
    path for path in Path(module, "build/compose/binaries/main").rglob("*")
    if path.suffix in {".deb", ".dmg", ".msi"}
)
if len(installers) != 1:
    raise SystemExit(f"Expected one installer, found {installers}")
source = installers[0]
name = f"{app}-desktop-{version}-{os_name}-{arch}{source.suffix}"
target = output / name
copyfile(source, target)
with target.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
(output / f"SHA256SUMS-desktop-{os_name}-{arch}.txt").write_text(f"{digest}  {name}\n")
print(target)
