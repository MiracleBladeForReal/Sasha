#!/usr/bin/env bash
# The emulator action executes each script line in a new shell. Keep result
# handling here so diagnostics cannot hide a failing instrumented test run.
set -u
test_status=0
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace || test_status=$?
adb pull /sdcard/Pictures/GalaxySensorLab-tests app/build/screenshots || true
adb logcat -d > app/build/emulator-logcat.txt || true
exit "$test_status"
