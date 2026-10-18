#!/usr/bin/env python3
"""Read-only website -> repository backup. No upload API or deployment credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.request import Request, urlopen

MAX_INDEX = 32 * 1024 * 1024
MAX_BLOB = 8 * 1024 * 1024
SHA = re.compile(r"[0-9a-f]{64}")
ID = re.compile(r"[a-z0-9][a-z0-9-]{0,79}")


def canonical(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode()


def fetch(url, limit):
    with urlopen(Request(url, headers={"Accept": "application/json"}), timeout=60) as response:
        # Public source only. Never attach a credential, including across redirects.
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Catalog response exceeds backup limit")
    return data


def snapshot(base, output, download=fetch):
    """Verify the entire publication set before writing any files; retain immutable history."""
    index = json.loads(download(base + "/api/designs/catalog/publications", MAX_INDEX))
    if index.get("schema") != 1 or not isinstance(index.get("serial"), int):
        raise ValueError("Unsupported publication index")
    previous_path = output / "publications.json"
    previous = json.loads(previous_path.read_bytes()) if previous_path.exists() else None
    if previous and index["serial"] < previous["serial"]:
        raise ValueError("Website serial rolled back; refusing to replace backup")
    revisions = index.get("revisions")
    if not isinstance(revisions, list):
        raise ValueError("Missing revisions")
    if previous:
        old = {(r["id"], r["revision"]): r for r in previous["revisions"]}
        new = {(r["id"], r["revision"]): r for r in revisions}
        if any(new.get(key) != value for key, value in old.items()):
            raise ValueError("Website lost or changed retained history")
    files = {"publications.json": canonical(index)}
    seen = set()
    for revision in revisions:
        design_id, number = revision["id"], revision["revision"]
        if not ID.fullmatch(design_id) or not isinstance(number, int) or number < 1:
            raise ValueError("Invalid revision identity")
        if (design_id, number) in seen:
            raise ValueError("Duplicate revision")
        seen.add((design_id, number))
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", revision.get("compilerVersion", "")):
            raise ValueError("Missing released compiler identity")
        source = revision["source"]
        if not source.get("catalogPin") or source.get("assets"):
            raise ValueError("Unpinned source or unbundled assets")
        files[f"sources/{design_id}/{number}.uid"] = canonical(source)
        entry = revision["entry"]
        sha = entry["sha256"]
        if not SHA.fullmatch(sha):
            raise ValueError("Invalid blob digest")
        path = f"blobs/{sha}.rc"
        if path not in files:
            data = download(base + "/designs/v1/" + path, MAX_BLOB)
            if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != sha:
                raise ValueError("Catalog blob size/digest mismatch")
            files[path] = data
    # Preflight immutable files before advancing the snapshot index.
    for path, data in files.items():
        target = output / path
        if path != "publications.json" and target.exists() and target.read_bytes() != data:
            raise ValueError("Immutable backup file changed")
    for path in sorted(files, key=lambda name: name == "publications.json"):
        data = files[path]
        target = output / path
        if target.exists() and target.read_bytes() == data:
            continue
        if path != "publications.json" and target.exists():
            raise ValueError("Immutable backup file changed")
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(target.suffix + ".tmp")
        temporary.write_bytes(data)
        temporary.replace(target)
    return len(revisions)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="https://ha.coo.ee")
    parser.add_argument("--output", type=Path, default=Path("catalog-backup"))
    args = parser.parse_args()
    if not args.url.startswith("https://"):
        parser.error("A public HTTPS catalog URL is required")
    print(f"Backed up {snapshot(args.url.rstrip('/'), args.output)} published revisions")


if __name__ == "__main__":
    main()
