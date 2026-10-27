#!/usr/bin/env python3
"""Publishes a card design library to a design server's channel, or deletes a channel.

  publish-design-library.py publish <library dir> <channel> [--commit SHA] [--title T]
      [--url URL] [--branch B] [--author A]
  publish-design-library.py delete <channel>

The library is what `./gradlew :card-designs:generateCardDesigns` writes
(card-designs/build/card-designs). The server keeps every file it was ever sent, by
content, so this asks which of the library's files it lacks
(POST /api/library/channels/missing) and uploads only those, with the whole library's
index (files.json); the server takes the rest from what it holds. See
docs/architecture/card-design-server.md, "Publishing".

Environment: DESIGN_SERVER_URL (e.g. https://ha.coo.ee) and LIBRARY_PUBLISH_TOKEN.
"""

import argparse
import hashlib
import io
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

CHANNELS = "/api/library/channels"


def request(method, path, body=None, content_type=None):
    url = os.environ["DESIGN_SERVER_URL"].rstrip("/") + path
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + os.environ["LIBRARY_PUBLISH_TOKEN"])
    req.add_header("User-Agent", "publish-design-library")
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=600) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bundle(root, index):
    """The library's index and the files the server lacks, zipped."""
    status, body = request(
        "POST",
        f"{CHANNELS}/missing",
        json.dumps(sorted(set(index.values()))).encode(),
        "application/json",
    )
    if status != 200:
        sys.exit(f"asking what the server lacks failed: {status} {body.decode(errors='replace')}")
    missing = set(json.loads(body))
    upload = sorted(path for path, digest in index.items() if digest in missing)

    zipped = io.BytesIO()
    with zipfile.ZipFile(zipped, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("files.json", json.dumps({"files": index}))
        for path in upload:
            z.write(os.path.join(root, path), path)
    print(
        f"{len(index)} files, uploading {len(upload)} the server lacks "
        f"({zipped.tell() / 1048576:.1f} MiB compressed)"
    )
    return zipped.getvalue()


def publish(args):
    root = args.library
    if not os.path.isfile(os.path.join(root, "manifest.json")):
        sys.exit(f"{root} has no manifest.json: generate the library first")
    index = {}
    for directory, _, files in os.walk(root):
        for name in files:
            path = os.path.join(directory, name)
            index[os.path.relpath(path, root).replace(os.sep, "/")] = sha256(path)

    params = {
        k: v
        for k, v in {
            "commit": args.commit,
            "title": args.title,
            "url": args.url,
            "branch": args.branch,
            "author": args.author,
        }.items()
        if v
    }
    query = ("?" + urllib.parse.urlencode(params)) if params else ""
    # The server keeps what it said it holds through the upload, but a restart loses that promise:
    # asked again, it names what it no longer has.
    for attempt in range(2):
        status, body = request(
            "PUT", f"{CHANNELS}/{args.channel}{query}", bundle(root, index), "application/zip"
        )
        if status == 200 or attempt or b"held here" not in body:
            break
        print(f"the server lost files it held, asking again: {body.decode(errors='replace')}")
    if status != 200:
        sys.exit(f"publishing failed: {status} {body.decode(errors='replace')}")
    info = json.loads(body)
    print(
        f"published {info['designs']} designs to {info['channel']}, serial {info['serial']}: "
        f"app URL {os.environ['DESIGN_SERVER_URL'].rstrip('/')}{info['api']}"
    )


def delete(args):
    status, body = request("DELETE", f"{CHANNELS}/{args.channel}")
    if status == 404:
        print(f"{args.channel}: nothing published")
    elif status != 204:
        sys.exit(f"deleting {args.channel} failed: {status} {body.decode(errors='replace')}")
    else:
        print(f"deleted {args.channel}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("publish")
    p.add_argument("library")
    p.add_argument("channel")
    for flag in ("commit", "title", "url", "branch", "author"):
        p.add_argument(f"--{flag}")
    p.set_defaults(run=publish)
    d = commands.add_parser("delete")
    d.add_argument("channel")
    d.set_defaults(run=delete)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
