#!/usr/bin/env python3
"""Checks a deployed card design server against this checkout (server-owned-card-designs.md, "Cutover").

Usage: scripts/check-design-server.py [--server https://ha.coo.ee] [--library card-designs/build/card-designs]
                                      [--sample 60]

Build the library first: ./gradlew :card-designs:generateCardDesigns

It checks, and exits non-zero when any fails:
1. Sources: GET /api/library/sources lists every card design checked in under
   card-designs/ui-builder/designs/, each the checked-in document unless edited in UI Builder.
2. Manifest: every design key the server publishes has the sha256 the local library captures, and
   the two publish the same keys.
3. Blobs: a sample of the server's blobs are the bytes their sha256 names, and the local blobs.
"""
import argparse, hashlib, json, os, random, sys, urllib.error, urllib.request


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def keys(manifest):
    out = {}
    for e in manifest["designs"]:
        k = tuple(sorted((a, str(b)) for a, b in e["key"].items())) + (("designId", str(e.get("designId"))),)
        out[k] = e["sha256"]
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--server", default="https://ha.coo.ee")
    p.add_argument("--library", default="card-designs/build/card-designs")
    p.add_argument("--designs", default="card-designs/ui-builder/designs")
    p.add_argument("--sample", type=int, default=60)
    a = p.parse_args()
    server = a.server.rstrip("/")
    failures = []

    # 1. Sources.
    checked_in = {f[: -len(".uid")]: json.load(open(os.path.join(a.designs, f)))
                  for f in sorted(os.listdir(a.designs)) if f.endswith(".uid")}
    try:
        served = {s["cardType"]: s for s in json.loads(fetch(server + "/api/library/sources"))["sources"]}
    except urllib.error.HTTPError as e:
        served = None
        failures.append(f"sources: {server}/api/library/sources answered {e.code}")
    if served is not None:
        missing = sorted(set(checked_in) - set(served))
        extra = sorted(set(served) - set(checked_in))
        differ = sorted(t for t in set(checked_in) & set(served)
                        if not served[t]["edited"] and served[t]["document"] != checked_in[t])
        edited = sorted(t for t, s in served.items() if s["edited"])
        print(f"sources: {len(served)} served, {len(checked_in)} checked in; edited in UI Builder: {edited or 'none'}")
        for label, ts in (("not served", missing), ("served, not checked in", extra), ("unedited but different", differ)):
            if ts:
                failures.append(f"sources {label}: {ts}")

    # 2. Manifest.
    live = keys(json.loads(fetch(server + "/designs/v1/manifest.json")))
    local = keys(json.load(open(os.path.join(a.library, "manifest.json"))))
    same = sum(1 for k in live if local.get(k) == live[k])
    diff = [k for k in live if k in local and local[k] != live[k]]
    only_live = [k for k in live if k not in local]
    only_local = [k for k in local if k not in live]
    print(f"manifest: {len(live)} keys served, {same} identical, {len(diff)} different, "
          f"{len(only_live)} only on the server, {len(only_local)} only local")
    for label, ks in (("different", diff), ("only on the server", only_live), ("only local", only_local)):
        if ks:
            types = sorted({dict(k)["cardType"] for k in ks})
            failures.append(f"manifest keys {label}: {len(ks)} ({', '.join(types)})")

    # 3. Blobs.
    shas = sorted(set(live.values()))
    random.seed(1)
    bad = []
    for sha in random.sample(shas, min(a.sample, len(shas))):
        body = fetch(f"{server}/designs/v1/blobs/{sha}.rc")
        path = os.path.join(a.library, "blobs", sha + ".rc")
        if hashlib.sha256(body).hexdigest() != sha or (os.path.exists(path) and open(path, "rb").read() != body):
            bad.append(sha)
    print(f"blobs: {min(a.sample, len(shas))} of {len(shas)} sampled, {len(bad)} wrong")
    if bad:
        failures.append(f"blobs wrong: {bad[:5]}")

    for f in failures:
        print("FAIL", f)
    print("OK" if not failures else f"{len(failures)} check(s) failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
