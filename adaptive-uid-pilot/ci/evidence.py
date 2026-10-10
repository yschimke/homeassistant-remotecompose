#!/usr/bin/env python3
"""Bound and compare pilot captures; stage only PNG data for the trusted audit."""
import argparse
import hashlib
import html
import json
from pathlib import Path
from PIL import Image, ImageChops


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read(root, relative):
    path = (root / relative).resolve(strict=True)
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"File outside evidence root: {relative}")
    if path.stat().st_size > 8_000_000:
        raise ValueError(f"Evidence too large: {relative}")
    return path.read_bytes()


def png(root, relative, extent):
    import io
    data = read(root, relative)
    with Image.open(io.BytesIO(data)) as image:
        if image.format != "PNG" or image.size != extent or max(image.size) > 1800:
            raise ValueError(f"Unexpected PNG extent: {relative}")
        return data, image.convert("RGBA")


def run(plan_path, root, stage=None):
    plan = json.loads(plan_path.read_text())
    captures = plan["captures"]
    ids = [c["previewId"] for c in captures]
    if len(ids) != 8 or len(set(ids)) != 8:
        raise ValueError("Expected exactly eight unique pilot captures")
    if stage is not None:
        stage.mkdir(parents=True, exist_ok=False)
        previews = []
        for c in captures:
            pid = c["previewId"]
            if not all(ch.isalnum() or ch == '-' for ch in pid):
                raise ValueError("Invalid capture ID")
            data, _ = png(root, f"previews/{pid}.png", (c["widthDp"] * c["density"], c["heightDp"] * c["density"]))
            (stage / f"{pid}.png").write_bytes(data)
            previews.append({"id": pid, "functionName": pid, "params": {"widthDp": c["widthDp"], "heightDp": c["heightDp"]}, "captures": [{"renderOutput": f"{pid}.png", "sha256": digest(data)}]})
        (stage / "previews.json").write_text(json.dumps({"previews": previews}, indent=2) + '\n')
        return
    manifest = json.loads(read(root, "references/index.json"))
    refs = {r["previewId"]: r for r in manifest["references"]}
    if len(manifest["references"]) != 8 or set(refs) != set(ids):
        raise ValueError("Missing, duplicate or unexpected references")
    diff_dir = root / "diffs"
    diff_dir.mkdir(exist_ok=True)
    results, rows, pixels = [], [], {}
    for c in captures:
        pid = c["previewId"]
        ref = refs[pid]
        extent = (c["widthDp"] * c["density"], c["heightDp"] * c["density"])
        candidate_path = f"previews/{pid}.png"
        ref_path = ref["raster"]["path"]
        candidate_bytes, candidate = png(root, candidate_path, extent)
        ref_bytes, reference = png(root, ref_path, extent)
        uid = read(root, ref["artifact"]["path"])
        if digest(ref_bytes) != ref["raster"]["sha256"] or digest(uid) != ref["source"]["attributes"]["documentSha256"]:
            raise ValueError(f"Reference hash mismatch: {pid}")
        pixels[pid] = (candidate.tobytes(), reference.tobytes())
        delta = ImageChops.difference(reference, candidate)
        mask = ImageChops.lighter(ImageChops.lighter(delta.getchannel('R'), delta.getchannel('G')), ImageChops.lighter(delta.getchannel('B'), delta.getchannel('A'))).point(lambda v: 255 if v else 0)
        changed = mask.histogram()[255]
        heatmap = Image.new('RGB', extent, 'white')
        heatmap.paste((210, 0, 100), mask=mask)
        diff_path = f"diffs/{pid}.png"
        heatmap.save(root / diff_path)
        results.append({"previewId": pid, "candidateSha256": digest(candidate_bytes), "referenceSha256": digest(ref_bytes), "referenceRevision": ref['source']['revision'], "changedPixels": changed, "totalPixels": extent[0] * extent[1], "diff": diff_path})
        imgs = ''.join(f'<figure><figcaption>{label}</figcaption><img src="{html.escape(path, quote=True)}"></figure>' for label, path in [('UID reference', ref_path), ('Exact pixel diff', diff_path), ('Compose actual', candidate_path)])
        rows.append(f'<h2>{html.escape(pid)} · {changed:,} changed pixels</h2><section>{imgs}</section>')
    for theme in ('light', 'dark'):
        tablet = [c['previewId'] for c in captures if c['widthDp'] == 840 and c['theme'] == theme]
        if len(tablet) != 2:
            raise ValueError('Expected tablet list/detail pair for each theme')
        for index, label in enumerate(('Compose', 'UID')):
            if pixels[tablet[0]][index] == pixels[tablet[1]][index]:
                raise ValueError(f'{label} tablet states are duplicate images: {theme}')
    report = {"schema": "adaptive-uid-evidence/v1", "comparison": "exact RGBA pixels; no perceptual tolerance", "captures": results}
    (root / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
    (root / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Adaptive UID comparison</title><style>body{font:16px system-ui;margin:24px}section{display:flex;gap:12px}figure{margin:0;flex:1;min-width:0}img{width:100%}figcaption{margin-bottom:8px}</style><h1>UID reference → diff → Compose</h1><p>White means unchanged; pink marks changed pixels. Exact fidelity does not establish UX quality.</p>' + ''.join(rows))
    print(json.dumps({"captures": len(results), "changedCaptures": sum(r['changedPixels'] > 0 for r in results)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--stage', type=Path)
    args = parser.parse_args()
    run(args.plan, args.root, args.stage)
