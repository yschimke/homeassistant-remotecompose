#!/usr/bin/env python3
"""Token-free local setup for Google's AppFunctions Testing Agent. See docs/testing/appfunctions.md."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
REVISION = "a3a2a89528ea14262b88029ab80b29497439a651"
PACKAGE = "ee.schimke.harc"
FUNCTION_PREFIX = "ee.schimke.terrazzo.assistant.TerrazzoAppFunctions#"
FUNCTIONS = {"findEntities", "getEntityState", "turnOn", "turnOff", "toggle"}
PATCH = Path(__file__).with_name("ephemeral-key.patch")


def agent_apk(agent):
    return agent / "agent/app/build/outputs/apk/standard/debug/app-standard-debug.apk"


def build_receipt(agent):
    return {"revision": REVISION,
            "patch_sha256": hashlib.sha256(PATCH.read_bytes()).hexdigest(),
            "apk_sha256": hashlib.sha256(agent_apk(agent).read_bytes()).hexdigest()}


def verified_build(agent):
    receipt = agent / ".terrazzo-build.json"
    if not agent_apk(agent).is_file() or not receipt.is_file():
        raise RuntimeError("Build the patched standard debug agent first.")
    expected = build_receipt(agent)
    if json.loads(receipt.read_text()) != expected:
        raise RuntimeError("APK or ephemeral-key patch changed; rebuild the agent first.")
    return expected


def run(args, *, cwd=None, capture=False):
    result = subprocess.run(
        [str(arg) for arg in args], cwd=cwd, check=True, text=True,
        stdout=subprocess.PIPE if capture else None, timeout=1800,
    )
    return result.stdout.strip() if capture else None


def adb_path():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidate = Path(sdk) / "platform-tools/adb" if sdk else None
    adb = str(candidate) if candidate and candidate.is_file() else shutil.which("adb")
    if not adb or "Android Debug Bridge" not in run([adb, "version"], capture=True):
        raise RuntimeError("No working adb. Set ANDROID_HOME to a real Android SDK (not a stub).")
    return adb


def device(serial):
    if not serial:
        raise RuntimeError("Pass --serial explicitly; never select a personal device implicitly.")
    adb = [adb_path(), "-s", serial]
    if run([*adb, "get-state"], capture=True) != "device":
        raise RuntimeError("Selected device is not authorized/online.")
    if int(run([*adb, "shell", "getprop", "ro.build.version.sdk"], capture=True)) < 36:
        raise RuntimeError("AppFunctions Testing Agent requires Android API 36 or newer.")
    return adb


def shell(adb, *args):
    # adb shell joins its arguments; quote again for the device's shell, especially JSON and '#'.
    return run([*adb, "shell", shlex.join(args)], capture=True)


def discover(adb):
    raw = shell(adb, "cmd", "app_function", "list-app-functions", "--package", PACKAGE)
    metadata = json.loads(raw)
    ids = set()

    def visit(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key == "functionId" and isinstance(child, list):
                    ids.update(item for item in child if isinstance(item, str))
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(metadata)
    expected = {FUNCTION_PREFIX + name for name in FUNCTIONS}
    if ids != expected:
        raise RuntimeError(f"Expected five Terrazzo function IDs; found {sorted(ids)}")
    for function_id in sorted(ids):
        enabled = shell(adb, "cmd", "app_function", "is-enabled", "--package", PACKAGE,
                        "--function", function_id)
        print(f"{function_id}: enabled={enabled}")


def prepare(agent):
    if not agent.exists():
        run(["git", "clone", "https://github.com/android/appfunctions.git", agent])
        run(["git", "checkout", "--detach", REVISION], cwd=agent)
    if run(["git", "rev-parse", "HEAD"], cwd=agent, capture=True) != REVISION:
        raise RuntimeError("Agent checkout differs from the reviewed revision; use a new directory.")
    applied = subprocess.run(["git", "apply", "--reverse", "--check", str(PATCH)], cwd=agent,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not applied:
        if run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=agent, capture=True):
            raise RuntimeError("Agent has local changes; refusing to patch them.")
        run(["git", "apply", "--check", PATCH], cwd=agent)
        run(["git", "apply", PATCH], cwd=agent)
    print(f"Prepared {REVISION} with a memory-only Gemini key in {agent}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["doctor", "prepare", "build-app", "build-agent",
                                            "discover", "install-agent", "launch-agent", "stop-agent"])
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--agent-dir", type=Path, default=ROOT / ".ci-dependencies/appfunctions")
    args = parser.parse_args()
    agent = args.agent_dir.resolve()
    if args.command == "doctor":
        print(run(["java", "-version"], capture=True))
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        print(f"Android SDK: {sdk or 'unset (adb must be on PATH)'}")
        if sdk:
            for relative in ["platforms/android-37.0/android.jar", "platforms/android-37.1/android.jar",
                             "build-tools/37.0.0/aapt2"]:
                print(f"{relative}: {'present' if (Path(sdk) / relative).is_file() else 'missing'}")
        print(run([adb_path(), "version"], capture=True))
        print(run([adb_path(), "devices", "-l"], capture=True))
        print(f"KVM exposed: {Path('/dev/kvm').exists()}")
        if args.serial:
            adb = device(args.serial)
            help_text = shell(adb, "cmd", "app_function", "help")
            if "list-app-functions" not in help_text:
                raise RuntimeError("Platform app_function service/commands unavailable.")
            print(help_text)
    elif args.command == "prepare":
        prepare(agent)
    elif args.command == "build-app":
        run([ROOT / "gradlew", ":app:assembleDebug", ":app:testDebugUnitTest",
             "-Pharc.crashReporter=none", "--console=plain"], cwd=ROOT)
    elif args.command == "build-agent":
        prepare(agent)
        receipt = agent / ".terrazzo-build.json"
        receipt.unlink(missing_ok=True)
        # Standard only: never pass a key to Gradle or embed one in BuildConfig/APK.
        run(["./gradlew", ":app:assembleStandardDebug", ":app:testStandardDebugUnitTest",
             "--console=plain"], cwd=agent / "agent")
        receipt.write_text(json.dumps(build_receipt(agent)) + "\n")
    else:
        adb = device(args.serial)
        if args.command == "discover":
            discover(adb)
        elif args.command == "stop-agent":
            shell(adb, "am", "force-stop", "com.example.appfunctions.agent")
        else:
            prepare(agent)
            if args.command == "install-agent":
                verified_build(agent)
                run([*adb, "install", "-r", agent_apk(agent)])
            else:
                expected = verified_build(agent)
                path = shell(adb, "pm", "path", "com.example.appfunctions.agent")
                if not path.startswith("package:") or "\n" in path:
                    raise RuntimeError("Install the patched standard debug agent first.")
                checksum = shell(adb, "sha256sum", path.removeprefix("package:")).split()[0]
                if checksum != expected["apk_sha256"]:
                    raise RuntimeError("Installed agent differs from the patched APK; reinstall it.")
                # Upstream handles API 37 allowlisting and shell-identity instrumentation.
                env = os.environ.copy()
                env["PATH"] = str(Path(adb[0]).parent) + os.pathsep + env.get("PATH", "")
                subprocess.run(["./startAppFunctionTestingAgent", "--serial", args.serial],
                               cwd=agent / "agent", env=env, check=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError) as error:
        sys.exit(f"AppFunctions setup failed: {error}")
