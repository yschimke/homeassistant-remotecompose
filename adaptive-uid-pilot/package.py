#!/usr/bin/env python3
"""Validate the capture binding and package only this publication's referenced artifacts."""
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parent
out = root / "build/pilot"
plan = json.loads((root / "references.json").read_text())
manifest = json.loads((out / "references/index.json").read_text())
refs = {ref["previewId"]: ref for ref in manifest["references"]}
expected = {capture["previewId"] for capture in plan["captures"]}
assert len(refs) == len(manifest["references"]) == len(expected) and set(refs) == expected
files = {"references/index.json"}
for capture in plan["captures"]:
    ref = refs[capture["previewId"]]
    candidate = f"previews/{capture['previewId']}.png"
    extent = (capture["widthDp"] * capture["density"], capture["heightDp"] * capture["density"])
    assert max(extent) <= 1800
    for relative in (candidate, ref["raster"]["path"]):
        path = (out / relative).resolve()
        assert path.is_relative_to(out.resolve())
        data = path.read_bytes()
        assert data[:8] == b"\x89PNG\r\n\x1a\n", relative
        assert struct.unpack(">II", data[16:24]) == extent, relative
        files.add(relative)
    assert hashlib.sha256((out / ref["raster"]["path"]).read_bytes()).hexdigest() == ref["raster"]["sha256"]
    uid_path = (out / ref["artifact"]["path"]).resolve()
    assert uid_path.is_relative_to(out.resolve())
    assert hashlib.sha256(uid_path.read_bytes()).hexdigest() == ref["source"]["attributes"]["documentSha256"]
    files.add(ref["artifact"]["path"])
archive = root / "build/dashboards.zip"
with ZipFile(archive, "w", ZIP_DEFLATED) as bundle:
    for relative in sorted(files):
        bundle.write(out / relative, relative)
print(f"Packaged {len(expected)} paired captures: {archive}")
