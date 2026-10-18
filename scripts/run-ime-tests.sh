#!/usr/bin/env bash
# Run on a disposable emulator; 320dpi makes these 320x568dp and 360x640dp phones.
set -euo pipefail

adb shell wm size "$1"
adb shell wm density 320
adb shell cmd overlay enable-exclusive --category "com.android.internal.systemui.navbar.$2"
adb shell settings put secure show_ime_with_hard_keyboard 1
# Disposable emulator settings; also exercise app overrides against either device theme.
adb shell cmd uimode night "${3:-no}"
if [[ "${4:-none}" != none ]]; then
  adb shell cmd overlay enable "com.android.internal.display.cutout.emulation.$4"
fi
# Lock rotation after overlays/theme changes so the landscape slot stays landscape.
adb shell wm fixed-to-user-rotation enabled
adb shell wm user-rotation lock "${5:-0}"
mkdir -p build/ime-evidence
adb shell wm size > build/ime-evidence/device.txt
adb shell wm density >> build/ime-evidence/device.txt
adb shell wm user-rotation >> build/ime-evidence/device.txt
adb shell dumpsys window displays >> build/ime-evidence/device.txt
adb shell cmd overlay list >> build/ime-evidence/device.txt

set +e
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.annotation=ee.schimke.terrazzo.AndroidWindowInsetsRegression \
  -Pandroid.testInstrumentationRunnerArguments.expectedRotation="${5:-0}" \
  -Pandroid.testInstrumentationRunnerArguments.expectedCutout="${4:-none}" \
  --stacktrace
test_status=$?
# Fail closed if a runner/filter change silently drops one of the regression classes.
python3 - <<'PYTESTS'
from pathlib import Path
import xml.etree.ElementTree as ET
expected = {
    "ee.schimke.terrazzo.AndroidImeScrollingTest": 3,
    "ee.schimke.terrazzo.AndroidEdgeToEdgeTest": 1,
    "ee.schimke.terrazzo.AndroidSystemBarThemeTest": 1,
}
actual = {}
for report in Path("app/build/outputs/androidTest-results/connected/debug").glob("TEST-*.xml"):
    for test in ET.parse(report).iter("testcase"):
        name = test.get("classname")
        actual[name] = actual.get(name, 0) + 1
if actual != expected:
    raise SystemExit(f"Incomplete inset regression run: expected {expected}, got {actual}")
print(f"Verified all {sum(actual.values())} inset regression tests executed")
PYTESTS
coverage_status=$?
if [[ "$coverage_status" != 0 ]]; then
  test_status=$coverage_status
fi
adb pull /sdcard/Pictures/TerrazzoImeTests build/ime-evidence
adb logcat -d -s TestRunner AndroidRuntime > build/ime-evidence/logcat.txt
exit "$test_status"
