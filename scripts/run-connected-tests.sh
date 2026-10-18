#!/usr/bin/env bash
# Pull screenshots before emulator-runner shuts down the device, including failed runs.
set -u
./gradlew connectedDebugAndroidTest -x :wear:connectedDebugAndroidTest --stacktrace
widget_test_status=$?
mkdir -p build/widget-test-screenshots
adb pull /sdcard/Pictures/TerrazzoWidgetTests build/widget-test-screenshots || true
adb pull /sdcard/Download/TerrazzoWidgetTests build/widget-test-screenshots || true
exit "$widget_test_status"
