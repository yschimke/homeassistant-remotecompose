#!/usr/bin/env python3
"""Validate the capture binding and package only this publication's referenced artifacts."""
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile, ZIP_DEFLATED


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def package(root):
    out = (root / "build/pilot").resolve()
    plan = json.loads((root / "references.json").read_text())
    manifest = json.loads((out / "references/index.json").read_text())
    refs = {ref["previewId"]: ref for ref in manifest["references"]}
    expected = {capture["previewId"] for capture in plan["captures"]}
    require(
        len(refs) == len(manifest["references"]) == len(expected) == len(plan["captures"])
        and set(refs) == expected,
        "Capture/reference IDs must match exactly, with no duplicates",
    )
    files = {"references/index.json"}
    for capture in plan["captures"]:
        ref = refs[capture["previewId"]]
        candidate = f"previews/{capture['previewId']}.png"
        extent = (capture["widthDp"] * capture["density"], capture["heightDp"] * capture["density"])
        require(0 < min(extent) and max(extent) <= 1800, f"Capture exceeds size cap: {candidate}")
        for relative in (candidate, ref["raster"]["path"]):
            path = (out / relative).resolve()
            require(path.is_relative_to(out), f"Image escapes bundle root: {relative}")
            data = path.read_bytes()
            require(len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n", f"Invalid PNG: {relative}")
            require(struct.unpack(">II", data[16:24]) == extent, f"Wrong image dimensions: {relative}")
            files.add(relative)
        require(
            hashlib.sha256((out / ref["raster"]["path"]).read_bytes()).hexdigest() == ref["raster"]["sha256"],
            f"Reference image hash mismatch: {capture['previewId']}",
        )
        uid_path = (out / ref["artifact"]["path"]).resolve()
        require(uid_path.is_relative_to(out), f"UID escapes bundle root: {uid_path}")
        require(
            hashlib.sha256(uid_path.read_bytes()).hexdigest() == ref["source"]["attributes"]["documentSha256"],
            f"UID hash mismatch: {capture['previewId']}",
        )
        files.add(ref["artifact"]["path"])
    archive = root / "build/dashboards.zip"
    with ZipFile(archive, "w", ZIP_DEFLATED) as bundle:
        for relative in sorted(files):
            bundle.write(out / relative, relative)
    print(f"Packaged {len(expected)} paired captures: {archive}")
    return archive


if __name__ == "__main__":
    package(Path(__file__).resolve().parent)
