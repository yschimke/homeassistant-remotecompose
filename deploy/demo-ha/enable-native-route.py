#!/usr/bin/env python3
"""Persist the native demo overlay in an existing Caddy stack's environment."""
import argparse
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile


def enable_route(stack, env_name=".env", demo_dir=None):
    stack = Path(stack).resolve(strict=True)
    demo_dir = Path(demo_dir or Path(__file__).parent).resolve(strict=True)
    env_file = (stack / env_name).resolve(strict=True)
    overlay = demo_dir / "caddy.native.yaml"
    if not overlay.is_file() or not (demo_dir / "native.caddy").is_file():
        raise ValueError("Native route files are missing; update the deployment bundle first")
    if not env_file.is_file():
        raise ValueError("Expected a regular environment file")
    if os.environ.get("COMPOSE_PATH_SEPARATOR", ":") != ":":
        raise ValueError("Only colon-separated COMPOSE_FILE lists are supported")

    def compose(path, *args, env=None, capture=False):
        return subprocess.run(
            ["docker", "compose", "--env-file", str(path), *args],
            cwd=stack, env=env, check=True, text=True,
            capture_output=capture,
        )

    # Capture privately: this output can contain deployment credentials.
    effective = compose(env_file, "config", "--environment", capture=True).stdout
    settings = dict(line.split("=", 1) for line in effective.splitlines() if "=" in line)
    files = settings.get("COMPOSE_FILE", "")
    if not files or any(not item for item in files.split(":")):
        raise ValueError("Set the stack's complete existing COMPOSE_FILE list before running")
    if settings.get("COMPOSE_PATH_SEPARATOR", ":") != ":":
        raise ValueError("Only colon-separated COMPOSE_FILE lists are supported")
    if str(overlay) not in files.split(":"):
        files += ":" + str(overlay)
    updates = {"COMPOSE_FILE": files, "NATIVE_DEMO_CONFIG_DIR": str(demo_dir)}
    if any(any(c in value for c in "'\r\n") for value in updates.values()):
        raise ValueError("Deployment paths cannot contain quotes or newlines")
    lines = env_file.read_text().splitlines()
    lines = [line for line in lines if not any(
        re.match(rf"^\s*(?:export\s+)?{key}\s*=", line) for key in updates
    )]
    lines.extend(f"{key}='{value}'" for key, value in updates.items())
    environment = {**os.environ, **updates}

    # Stage beside .env so relative Compose paths retain their usual context.
    fd, staged_name = tempfile.mkstemp(prefix=".native-route-", dir=env_file.parent)
    staged = Path(staged_name)
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write("\n".join(lines) + "\n")
        compose(staged, "config", "--quiet", env=environment)
        compose(staged, "run", "--rm", "--no-deps", "caddy", "validate",
                "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile", env=environment)
        backup_fd, backup_name = tempfile.mkstemp(prefix=env_file.name + ".backup-", dir=env_file.parent)
        with os.fdopen(backup_fd, "wb") as backup:
            backup.write(env_file.read_bytes())
        # Backups are private even if the original environment file was readable.
        staged.chmod(stat.S_IMODE(env_file.stat().st_mode) & 0o600)
        staged.replace(env_file)
        print(f"Saved native route settings; private backup: {backup_name}", flush=True)
        compose(env_file, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "caddy",
                env=environment)
    finally:
        staged.unlink(missing_ok=True)
    print("Caddy recreated. Verify https://ha-demo.coo.ee/lovelace/home (demo / demo).")
    print("If a deployment job overrides COMPOSE_FILE, append the native overlay there too.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stack", help="Existing Caddy stack directory")
    parser.add_argument("--env-file", default=".env", help="Existing stack environment file")
    args = parser.parse_args()
    try:
        enable_route(args.stack, args.env_file)
    except subprocess.CalledProcessError:
        # Never echo captured config --environment output or its credentials.
        parser.exit(1, "Docker Compose failed; route setup is incomplete.\n")
    except (OSError, ValueError) as error:
        parser.exit(1, f"Route setup stopped: {error}\n")


if __name__ == "__main__":
    main()
