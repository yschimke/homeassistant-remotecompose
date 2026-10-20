#!/usr/bin/env bash
# Runs inside android-emulator-runner (baseline-profiles.yml): generate the profile, then the
# macrobenchmarks.
#
#   scripts/run-baseline-profiles.sh <module> <generate task> <benchmark task> <profiles dir>
#
# Before generating, installs the target and tries the compile macrobenchmark starts with, so a
# device that refuses it shows why (macrobenchmark's own error carries only the command's output,
# and AGP has uninstalled the app by the time a failed run could be inspected).
#
# After generating, prints the profiles as a base64 tarball between markers: the job log is the one
# place an agent without artifact access can read them back from.
set -uo pipefail

module=$1
generate_task=$2
benchmark_task=$3
profiles_dir=$4
target=ee.schimke.harc
out=build/baseline-profiles
mkdir -p "$out"

{
  echo "## dex2oat properties"
  adb shell getprop | grep -E 'dex2oat|cpu.?set|ro.product.cpu' || true
  adb shell nproc 2>&1 || true
  # Wear images ship a dex2oat CPU set that names CPUs a small emulator lacks, which fails every
  # compile. Clear it where the image lets adb be root; the 4-core AVD covers the rest.
  if [[ "$module" == wear ]] && adb root 2>&1 | grep -q -E 'restarting|already running as root'; then
    adb wait-for-device
    for prop in dalvik.vm.dex2oat-cpu-set dalvik.vm.boot-dex2oat-cpu-set \
      dalvik.vm.image-dex2oat-cpu-set dalvik.vm.background-dex2oat-cpu-set; do
      adb shell "test -n \"\$(getprop $prop)\" && setprop $prop ''" || true
    done
    echo "cleared dex2oat CPU sets as root"
  fi
  apk=$(ls "$module"/build/outputs/apk/nonMinifiedRelease/*.apk | head -1)
  echo "## install $apk"
  adb install -r "$apk" 2>&1
  for mode in "--reset" "-f -m verify" "-f -m speed-profile"; do
    echo "## cmd package compile $mode $target"
    # shellcheck disable=SC2086 # $mode is a list of flags.
    adb shell cmd package compile $mode "$target" 2>&1
  done
  echo "## dumpsys package $target (dexopt)"
  adb shell dumpsys package "$target" 2>&1 | grep -i -A3 -E 'dexopt|compil|\[status' | head -40
  echo "## logcat"
  # Warnings and errors only (threadtime's 5th column is the level): the exception heads.
  adb logcat -d 2>&1 | grep -E 'dex2oat|ArtService|installd|PackageManager' \
    | awk '$5 ~ /^[WEF]$/' | grep -v '^.*	at ' | head -80
} > "$out/compile-probe.txt" 2>&1
cat "$out/compile-probe.txt"

adb logcat -c
./gradlew --stacktrace --max-workers=2 "$generate_task"
status=$?

if [[ "$status" -eq 0 ]]; then
  echo "::group::Generated profiles (base64 tar.gz)"
  echo "BASELINE-PROFILES-BEGIN $module"
  tar czf - "$profiles_dir" | base64 -w 0
  echo
  echo "BASELINE-PROFILES-END $module"
  echo "::endgroup::"

  # shellcheck disable=SC2086 # BENCHMARK_ARGS is a list of Gradle arguments.
  ./gradlew --stacktrace --max-workers=2 "$benchmark_task" ${BENCHMARK_ARGS:-}
  status=$?
fi

if [[ "$status" -ne 0 ]]; then
  adb logcat -d > "$out/logcat.txt" || true
  grep -n -E "$target|AndroidRuntime|FATAL" "$out/logcat.txt" | tail -60 > "$out/device.txt" || true
fi
exit "$status"
