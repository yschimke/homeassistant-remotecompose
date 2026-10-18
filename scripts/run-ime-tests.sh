#!/usr/bin/env bash
# Run on a disposable emulator; 320dpi makes these 320x568dp and 360x640dp phones.
set -euo pipefail

adb shell wm size "$1"
adb shell wm density 320
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation "${5:-0}"
adb shell cmd overlay enable-exclusive --category "com.android.internal.systemui.navbar.$2"
adb shell settings put secure show_ime_with_hard_keyboard 1
# Disposable emulator settings; also exercise app overrides against either device theme.
adb shell cmd uimode night "${3:-no}"
if [[ "${4:-none}" != none ]]; then
  adb shell cmd overlay enable "com.android.internal.display.cutout.emulation.$4"
fi
mkdir -p build/ime-evidence
adb shell wm size > build/ime-evidence/device.txt
adb shell wm density >> build/ime-evidence/device.txt
adb shell cmd overlay list >> build/ime-evidence/device.txt

set +e
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ee.schimke.terrazzo.AndroidImeScrollingTest,ee.schimke.terrazzo.AndroidEdgeToEdgeTest,ee.schimke.terrazzo.AndroidSystemBarThemeTest \
  --stacktrace
test_status=$?
adb pull /sdcard/Pictures/TerrazzoImeTests build/ime-evidence
adb logcat -d -s TestRunner AndroidRuntime > build/ime-evidence/logcat.txt
exit "$test_status"
