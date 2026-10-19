#!/usr/bin/env python3
"""
Count Lovelace card types across the dashboards checked into this repo.

Layout containers (grid, stacks, conditional) are counted separately: they
place cards but draw nothing of their own, so they never decide how a
dashboard looks under an uber theme. Tile cards are also split by entity
domain, since a sensor tile and a light tile render differently.

Usage:  scripts/card-usage.py [--top N] [dashboard.json|.yaml ...]
With no paths, scans the bundled dashboards and the demo fixtures.
"""
import argparse
import collections
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_GLOBS = [
    "terrazzo-core/src/androidMain/resources/dashboards/*/lovelace_config.json",
    "deploy/demo-ha/dashboard.json",
    "design-server/src/main/resources/dashboard/fixture.json",
    "integration/config/ui-lovelace.yaml",
]
LAYOUT = {"grid", "vertical-stack", "horizontal-stack", "conditional"}
# `type` keys that are not cards: views, sections, features, actions, badges.
NOT_CARDS = {"sections", "masonry", "panel", "sidebar"}


def load(path: Path):
    if path.suffix in (".yaml", ".yml"):
        try:
            import yaml  # optional: only needed for YAML dashboards
        except ModuleNotFoundError:
            print(f"skipping {path}: pip install pyyaml to include YAML dashboards", file=sys.stderr)
            return None
        return yaml.safe_load(path.read_text())
    return json.loads(path.read_text())


def cards(node, inside_card=False):
    """Yield every card dict: anything in a `cards`/`card` slot."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "cards" and isinstance(value, list):
                for child in value:
                    if isinstance(child, dict) and isinstance(child.get("type"), str):
                        yield child
                    yield from cards(child, True)
            elif key == "card" and isinstance(value, dict):
                yield value
                yield from cards(value, True)
            elif isinstance(value, (dict, list)):
                yield from cards(value, inside_card)
    elif isinstance(node, list):
        for child in node:
            yield from cards(child, inside_card)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--top", type=int, default=4)
    args = parser.parse_args()
    paths = args.paths or sorted(p for g in DEFAULT_GLOBS for p in ROOT.glob(g))

    content, layout, tiles = collections.Counter(), collections.Counter(), collections.Counter()
    dashboards_using = collections.Counter()
    scanned = 0
    for path in paths:
        dashboard = load(path)
        if dashboard is None:
            continue
        scanned += 1
        seen = set()
        for card in cards(dashboard):
            kind = card["type"]
            if kind in NOT_CARDS:
                continue
            if kind in LAYOUT:
                layout[kind] += 1
                continue
            content[kind] += 1
            seen.add(kind)
            if kind == "tile":
                tiles[str(card.get("entity", "?")).split(".")[0]] += 1
        dashboards_using.update(seen)

    total = sum(content.values())
    print(f"{scanned} dashboards, {total} content cards, {sum(layout.values())} layout containers\n")
    print(f"{'cards':>6} {'share':>6} {'dashboards':>10}  type")
    for i, (kind, n) in enumerate(content.most_common()):
        marker = "*" if i < args.top else " "
        print(f"{n:6d} {n / total:6.1%} {dashboards_using[kind]:10d} {marker}{kind}")
    top = content.most_common(args.top)
    print(f"\ntop {args.top} cover {sum(n for _, n in top) / total:.1%} of content cards")
    print("\ntile by domain: " + ", ".join(f"{d} {n}" for d, n in tiles.most_common(8)))
    print("layout: " + ", ".join(f"{k} {n}" for k, n in layout.most_common()))


if __name__ == "__main__":
    main()
