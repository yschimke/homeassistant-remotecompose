#!/usr/bin/env python3
"""Writes the design server's library sources into the repository's card designs.

Usage: import-card-designs.py <sources.json> <designs-dir>

<sources.json> is `GET /api/library/sources`: {"sources": [{"cardType", "edited",
"revision"?, "document"}]}. Each document is written to <designs-dir>/<cardType>.uid,
formatted as the checked-in designs are, so an unchanged design is no diff. A card
type the server no longer has is left alone: removing a design is a code change,
not an import.
"""

import json
import pathlib
import re
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    sources = json.loads(pathlib.Path(sys.argv[1]).read_text())["sources"]
    designs = pathlib.Path(sys.argv[2])
    designs.mkdir(parents=True, exist_ok=True)
    for source in sources:
        card_type = source["cardType"]
        if not re.fullmatch(r"[a-z0-9_:-]+", card_type):
            raise SystemExit(f"refusing card type {card_type!r}: not a file name")
        document = source["document"]
        if document.get("catalogPin", {}).get("systemId") != "ha-cards":
            raise SystemExit(f"{card_type}: not an ha-cards design")
        document["id"] = card_type
        path = designs / f"{card_type}.uid"
        path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n")
        state = "edited" if source.get("edited") else "checked in"
        print(f"{path}: {state}, revision {source.get('revision', '-')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
