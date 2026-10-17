#!/usr/bin/env python3
"""Keep independently published player modules on the player BOM's versions."""
import argparse
from pathlib import Path
import sys
try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib


def verify(root):
    with (root / "gradle/libs.versions.toml").open("rb") as stream:
        libraries = tomllib.load(stream)["libraries"]
    players = {
        name: entry for name, entry in libraries.items()
        if entry.get("module", "").startswith("ee.schimke.composeai:rc-player-")
    }
    errors = []
    bom = libraries.get("rc-players-bom", {})
    if bom.get("module") != "ee.schimke.composeai:rc-players-bom":
        errors.append("Declare rc-players-bom: player releases do not republish every module.")
    for name, entry in players.items():
        if "version" in entry:
            errors.append(f"{name} must take its version from rc-players-bom, not a shared module pin.")
        accessor = "libs." + name.replace("-", ".")
        for build in root.glob("*/build.gradle.kts"):
            text = build.read_text()
            if accessor in text and "libs.rc.players.bom" not in text:
                errors.append(f"{build.relative_to(root)} consumes {name} without the player BOM.")
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    errors = verify(parser.parse_args().root)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)
    print("Player modules use the BOM's independently published versions.")
