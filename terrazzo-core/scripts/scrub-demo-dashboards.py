#!/usr/bin/env python3
"""Second-pass scrub of the homeassistant-agents dashboard fixtures
before bundling them as the in-app demo.

The first pass (_sanitize.py in homeassistant-agents) redacts IPs,
MACs, tokens, lat/lon, sensitive attribute keys, etc. but keeps entity
ids and friendly names because that repo is treating them as non-secret.

For an app published to the Play Store, we want the entity ids + friendly
names re-keyed too — anything that looks like a device serial (Bambu
printer SN, UniFi MAC-fragment camera channels, Meshcore node ids,
Blink network ids, Octopus meter ids, etc.) gets replaced with a stable
readable pseudonym, applied consistently across the lovelace_config.json
AND entity_states.json so card-to-entity references survive.

Usage:
    python3 terrazzo-core/scripts/scrub-demo-dashboards.py \\
        --src ~/workspace/homeassistant-agents/dashboards \\
        --dst terrazzo-core/src/androidMain/resources/dashboards
"""
import argparse
import json
import re
from pathlib import Path

# Tokens inside entity ids (split by `_` / `.`) that look like device
# serials. We scrub anything that's:
#  - 8+ chars long, AND mixed letters+digits  (e.g. 22e8bj5b2200526)
#  - 9+ pure-digit chars                       (e.g. 1200028620880 meter id)
#  - 6+ pure-hex chars with at least one digit (e.g. fa27efd5b4 meshcore id)
SERIAL_TOKEN_RES = [
    re.compile(r"^(?=.*[a-z])(?=.*[0-9])[a-z0-9]{8,}$"),
    re.compile(r"^[0-9]{9,}$"),
    re.compile(r"^[0-9a-f]{6,}$"),
]

# Entity-id rewrites — when the natural token-by-token scrub misses
# a meaningful pseudonym, hardcode it. Applied before the generic
# token pass.
ENTITY_ID_REWRITES = [
    (re.compile(r"^alarm_control_panel\.blink_[a-z0-9_]+$"), "alarm_control_panel.blink_panel"),
    (re.compile(r"^binary_sensor\.blink_[a-z0-9_]+_motion$"), "binary_sensor.blink_motion"),
    (re.compile(r"^binary_sensor\.blink_[a-z0-9_]+_status$"), "binary_sensor.blink_status"),
]

# Attributes whose values reveal personal/network identifiers; replaced
# with `<redacted>` regardless of shape.
EXTRA_REDACT_KEYS = {
    "network_id",
    "region_id",
    "original_name",
    "raw_value",
    "device_id",
    "config_entry_id",
    "area_id",
    "name_template",
}


def looks_like_serial(tok: str) -> bool:
    return any(p.match(tok) for p in SERIAL_TOKEN_RES)


# Friendly-name leaks: serial-shaped tokens in display text (mixed
# upper-and-lower or 8+ digit runs).
FRIENDLY_LEAK_RE = re.compile(r"\b[A-Z0-9]{8,}\b|\b[a-f0-9]{8,}\b|\b[0-9]{9,}\b")


def make_id_mapper():
    """Build a function that scrubs an entity id string in-place,
    keeping a stable per-token mapping so referenced ids survive the
    round-trip."""
    token_map: dict[str, str] = {}
    counter = {"n": 0}

    def map_token(tok: str) -> str:
        if tok in token_map:
            return token_map[tok]
        counter["n"] += 1
        # Keep some readability: short pseudonyms like sn1, sn2, ...
        repl = f"sn{counter['n']}"
        token_map[tok] = repl
        return repl

    def scrub_id(eid: str) -> str:
        for pattern, replacement in ENTITY_ID_REWRITES:
            if pattern.match(eid):
                return replacement
        if "." not in eid:
            return eid
        domain, rest = eid.split(".", 1)
        parts = rest.split("_")
        out_parts = [map_token(p) if looks_like_serial(p) else p for p in parts]
        return f"{domain}.{'_'.join(out_parts)}"

    return scrub_id, token_map


def scrub_string(s: str, token_map: dict[str, str], scrub_id) -> str:
    # First: scrub any embedded `<domain>.<entity_id>` references
    # (jinja templates, attribute strings, ...).
    def _replace_eid(m: re.Match) -> str:
        return scrub_id(m.group())
    out = re.sub(r"\b[a-z_]+\.[a-zA-Z0-9_]+\b", _replace_eid, s)
    # Apply token map (case-insensitive) to any leftover reference,
    # using non-alphanumeric boundaries (so `_serial_` matches even
    # though `_` is a regex word char).
    for k, v in sorted(token_map.items(), key=lambda kv: -len(kv[0])):
        out = re.sub(
            rf"(?i)(?<![A-Za-z0-9]){re.escape(k)}(?![A-Za-z0-9])",
            v,
            out,
        )
    # Strip remaining serial-shaped tokens from display text. Use
    # alphanumeric-only boundaries (treats `_` as a separator).
    def _redact(m: re.Match) -> str:
        tok = m.group()
        if any(p.match(tok.lower()) for p in SERIAL_TOKEN_RES):
            return "X" * len(tok)
        return tok
    out = re.sub(
        r"(?<![A-Za-z0-9])[A-Za-z0-9]{8,}(?![A-Za-z0-9])",
        _redact,
        out,
    )
    return out


def scrub_json(node, scrub_id, token_map):
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            new_k = scrub_id(k) if "." in k and re.match(r"^[a-z_]+\.", k) else k
            if k in EXTRA_REDACT_KEYS:
                out[new_k] = "<redacted>"
            else:
                out[new_k] = scrub_json(v, scrub_id, token_map)
        return out
    if isinstance(node, list):
        return [scrub_json(x, scrub_id, token_map) for x in node]
    if isinstance(node, str):
        if re.match(r"^[a-z_]+\.[a-zA-Z0-9_]+$", node):
            return scrub_id(node)
        return scrub_string(node, token_map, scrub_id)
    return node


def collect_entity_ids(states: dict, config: dict, scrub_id):
    """Pre-warm the mapping by scrubbing every entity id we see, so
    string scrubs that reference those ids in friendly-name attributes
    or templates still find them in the cache."""
    for eid in states.keys():
        scrub_id(eid)
    def walk(node):
        if isinstance(node, dict):
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
        elif isinstance(node, str) and re.match(r"^[a-z_]+\.[a-zA-Z0-9_]+$", node):
            scrub_id(node)
    walk(config)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, type=Path)
    ap.add_argument("--dst", required=True, type=Path)
    args = ap.parse_args()

    args.dst.mkdir(parents=True, exist_ok=True)
    for board in sorted(args.src.iterdir()):
        if not board.is_dir():
            continue
        cfg_in = board / "lovelace_config.json"
        states_in = board / "entity_states.json"
        if not (cfg_in.exists() and states_in.exists()):
            continue
        out_dir = args.dst / board.name
        out_dir.mkdir(parents=True, exist_ok=True)

        cfg = json.loads(cfg_in.read_text())
        states = json.loads(states_in.read_text())

        scrub_id, token_map = make_id_mapper()
        collect_entity_ids(states, cfg, scrub_id)

        cfg_out = scrub_json(cfg, scrub_id, token_map)
        states_out = scrub_json(states, scrub_id, token_map)

        (out_dir / "lovelace_config.json").write_text(
            json.dumps(cfg_out, indent=2, ensure_ascii=False)
        )
        (out_dir / "entity_states.json").write_text(
            json.dumps(states_out, indent=2, ensure_ascii=False)
        )
        print(f"  {board.name}: {len(token_map)} unique serial tokens scrubbed")


if __name__ == "__main__":
    main()
