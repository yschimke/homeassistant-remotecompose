#!/usr/bin/env bash
# Runs inside android-emulator-runner (baseline-profiles.yml): generate the profile, then the
# macrobenchmarks. On a failure, keep what the device says about compiling the target before the
# emulator shuts down — macrobenchmark's own error carries only `cmd package compile`'s output.
#
#   scripts/run-baseline-profiles.sh <generate task> <benchmark task>
set -uo pipefail

generate_task=$1
benchmark_task=$2
target=ee.schimke.harc

adb logcat -c
./gradlew --stacktrace --max-workers=2 "$generate_task"
status=$?
if [[ "$status" -eq 0 ]]; then
  # shellcheck disable=SC2086 # BENCHMARK_ARGS is a list of Gradle arguments.
  ./gradlew --stacktrace --max-workers=2 "$benchmark_task" ${BENCHMARK_ARGS:-}
  status=$?
fi

if [[ "$status" -ne 0 ]]; then
  out=build/baseline-profiles
  mkdir -p "$out"
  {
    echo "## getprop (build)"
    adb shell getprop | grep -E 'ro\.(build|product)\.|dalvik\.vm\.|pm\.dexopt' || true
    echo "## pm path $target"
    adb shell pm path "$target" || true
    echo "## cmd package compile -f -m verify $target"
    adb shell cmd package compile -f -m verify "$target" 2>&1 || true
    echo "## cmd package compile --reset $target"
    adb shell cmd package compile --reset "$target" 2>&1 || true
    echo "## dumpsys package dexopt"
    adb shell dumpsys package dexopt 2>&1 | grep -A20 "\[$target\]" || true
  } > "$out/device.txt" 2>&1
  adb logcat -d > "$out/logcat.txt" || true
  grep -n -i -E 'dex2oat|dexopt|artd|PackageManager.*compile' "$out/logcat.txt" | tail -80 \
    >> "$out/device.txt" || true
fi
exit "$status"
