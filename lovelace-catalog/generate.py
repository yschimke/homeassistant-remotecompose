#!/usr/bin/env python3
"""Publish a data-only catalog through the same ui-builder.json seam as compiled catalogs."""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent


def generate(output):
    palette = json.loads((ROOT / "palette.json").read_text())
    spec = json.loads((ROOT / "catalog.spec.json").read_text())
    builtins = dict(palette["structure"])
    for card in palette["cardTypes"]:
        builtins[f"lovelace/card/{card}"] = {
            "role": "decoration", "shelfRole": "Leaf", "traits": ["LovelaceCard"],
            "displayName": card.replace("-", " ").title(), "group": "Cards",
            "canvas": "lovelace/card", "slots": {},
            "properties": palette["cardProperties"], "modifierCapabilities": [],
        }
    semantics = {
        "platform": "lovelace", "platformLabel": spec["title"],
        "componentIdPrefix": "lovelace/",
        "frame": {"adapter": "frame/rect", "seedDevice": "spec:width=1200dp,height=900dp,dpi=160"},
        "composeSourceExport": {"adapter": "lovelace-config", "version": 1},
        "newDesign": {"description": "Edit dashboard views, sections and cards. The host owns Home Assistant data and saves."},
        "builtins": builtins, "components": {},
        "templates": ["ui-builder/designs/dashboard.json"],
    }
    # A declaration-only catalog has no component record or executable bundle to fabricate.
    builder = {"schema": "compose-ui-builder-catalog/v1", "catalog": {"id": "lovelace", "title": spec["title"]}, "statusSemantics": semantics}
    manifest = {"schema": "design-parity-catalog/v1", "system": "lovelace", "title": spec["title"], "components": [], "uiBuilderFile": "ui-builder.json"}
    output.mkdir(parents=True, exist_ok=True)
    for name, value in [("ui-builder.json", builder), ("catalog.json", manifest)]:
        (output / name).write_text(json.dumps(value, indent=2) + "\n")
    seed = {"schema": "compose-ui-builder-document/v1-candidate", "id": "dashboard", "template": "dashboard", "title": "Home Assistant dashboard", "revision": 0,
        "catalogPin": {"systemId": "lovelace", "catalogRevision": "candidate", "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 1200, "heightDp": 900, "density": 1, "theme": "light", "locale": "en-US", "fontScale": 1, "layoutDirection": "ltr"},
        "stateVariables": {}, "roots": ["dashboard"], "nodes": {
            "dashboard": {"id": "dashboard", "componentId": "lovelace/dashboard", "properties": {"title": {"type": "string", "value": "Home"}}, "slots": {"views": ["home"]}},
            "home": {"id": "home", "componentId": "lovelace/view", "properties": {"title": {"type": "string", "value": "Home"}}, "slots": {"sections": ["section"]}},
            "section": {"id": "section", "componentId": "lovelace/section", "properties": {}, "slots": {"cards": []}},
        }}
    seed_path = output / "ui-builder/designs/dashboard.json"
    seed_path.parent.mkdir(parents=True, exist_ok=True)
    seed_path.write_text(json.dumps(seed, indent=2) + "\n")
    (output / "templates.json").write_text((ROOT / "templates.json").read_text())
    return builder


if __name__ == "__main__":
    generate(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT.parent / "build/lovelace-catalog")
