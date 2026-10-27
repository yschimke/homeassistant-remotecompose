#!/usr/bin/env bash
# Run LiveCardInflationTest across a set of AVDs and print a one-row-per-API summary.
#
# Boots each AVD sequentially (`android emulator start <avd>` is blocking), installs the app and
# androidTest APKs, invokes `am instrument` for just this class, captures stdout, stops the AVD.
# Other adb devices (real phones, other emulators) are untouched: this targets each emulator by
# its own serial.
set -euo pipefail

AVDS=(rc_api35 Medium_Phone_API_36.1 medium_phone_api37)
TEST_CLASS=ee.schimke.terrazzo.notify.LiveCardInflationTest
APP_PKG=ee.schimke.harc
RUNNER="$APP_PKG.test/androidx.test.runner.AndroidJUnitRunner"

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

echo "[matrix] building debug + androidTest APKs"
./scripts/build-brief-wrapper.sh app:assembleDebug app:assembleDebugAndroidTest >/dev/null 2>&1 \
  || build-brief ./gradlew app:assembleDebug app:assembleDebugAndroidTest

APP_APK=$(ls -t app/build/outputs/apk/debug/app-debug.apk 2>/dev/null | head -1)
TEST_APK=$(ls -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk 2>/dev/null | head -1)
[[ -f "$APP_APK" && -f "$TEST_APK" ]] || { echo "APKs not found; build failed" >&2; exit 1; }
echo "[matrix] app=$APP_APK test=$TEST_APK"

for avd in "${AVDS[@]}"; do
  echo
  echo "============================================================"
  echo "[matrix] AVD: $avd"
  echo "============================================================"

  # Record currently-attached emulator serials; the one that appears after start is ours.
  before=$(adb devices | awk '/emulator-[0-9]+\s+device/ {print $1}' | sort -u)
  echo "[matrix] starting emulator (blocking until ready)"
  android emulator start "$avd" >/dev/null 2>&1 &
  START_PID=$!
  # Wait for a new emulator serial to appear, up to 300s.
  SERIAL=""
  for _ in $(seq 1 150); do
    sleep 2
    now=$(adb devices | awk '/emulator-[0-9]+\s+device/ {print $1}' | sort -u)
    newcomer=$(comm -13 <(printf '%s\n' $before) <(printf '%s\n' $now))
    if [[ -n "$newcomer" ]]; then
      SERIAL=$(printf '%s\n' $newcomer | head -1)
      break
    fi
  done
  if [[ -z "$SERIAL" ]]; then
    echo "[matrix] $avd: emulator did not come up"
    kill $START_PID 2>/dev/null || true
    continue
  fi
  echo "[matrix] serial=$SERIAL"

  # Wait for sys.boot_completed.
  for _ in $(seq 1 60); do
    boot=$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    [[ "$boot" == "1" ]] && break
    sleep 2
  done
  adb -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true

  api=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
  echo "[matrix] API $api"

  echo "[matrix] installing APKs"
  adb -s "$SERIAL" install -r -g "$APP_APK" >/dev/null
  adb -s "$SERIAL" install -r -g "$TEST_APK" >/dev/null

  echo "[matrix] running $TEST_CLASS"
  adb -s "$SERIAL" logcat -c >/dev/null 2>&1 || true
  OUT=$(adb -s "$SERIAL" shell "am instrument -w -r -e class $TEST_CLASS $RUNNER" 2>&1 || true)
  echo "----- instrument output -----"
  echo "$OUT" | sed -n '/INSTRUMENTATION_RESULT\|INSTRUMENTATION_STATUS\|liveCardMatrix\|INSTRUMENTATION_CODE/p'
  echo "----- logcat (test tag) -----"
  adb -s "$SERIAL" logcat -d -s LiveCardInflationTest NotifContentInflater | tail -80 || true

  echo "[matrix] stopping emulator"
  android emulator stop "$avd" >/dev/null 2>&1 || adb -s "$SERIAL" emu kill || true
  wait $START_PID 2>/dev/null || true
  sleep 2
done

echo
echo "[matrix] done"
