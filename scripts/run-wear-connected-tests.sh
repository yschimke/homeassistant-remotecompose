#!/usr/bin/env bash
# Keep runtime evidence before emulator-runner shuts down the Wear device.
set -uo pipefail

mkdir -p build/wear-runtime
adb shell getprop > build/wear-runtime/device-properties.txt
adb shell pm list libraries > build/wear-runtime/shared-libraries.txt
adb shell wm size > build/wear-runtime/display-size.txt
# Bound every captured frame, including when using a different AVD profile locally.
adb shell wm size 384x384
adb logcat -c

./gradlew :wear:connectedDebugAndroidTest --stacktrace --max-workers=2
test_status=$?

# AGP can uninstall the app after instrumentation. Install it again for an
# actual activity launch and screenshot; this does not prove widget pinning.
smoke_status=0
adb install -r wear/build/outputs/apk/debug/wear-debug.apk || smoke_status=$?
if [[ "$smoke_status" -eq 0 ]]; then
  adb shell am start -W -n ee.schimke.harc/ee.schimke.terrazzo.wear.WearMainActivity \
    > build/wear-runtime/activity-start.txt || smoke_status=$?
  # Wait for the activity's first frame after am start has resumed it.
  sleep 3
  adb shell dumpsys activity activities > build/wear-runtime/activities.txt
  adb exec-out screencap -p > build/wear-runtime/app-launch.png || smoke_status=$?
fi
adb logcat -d > build/wear-runtime/logcat.txt || true

if [[ "$test_status" -ne 0 ]]; then
  exit "$test_status"
fi
exit "$smoke_status"
