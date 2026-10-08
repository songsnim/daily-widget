#!/bin/sh
# Build, wait for the phone, install, grant the two permissions, refresh the widget.
set -e
cd "$(dirname "$0")"
./gradlew assembleDebug -q
echo "폰 연결 대기 중..."
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set dev.daily.widget MANAGE_EXTERNAL_STORAGE allow
adb shell appops set dev.daily.widget GET_USAGE_STATS allow
adb shell am start -n dev.daily.widget/.MainActivity >/dev/null  # settings screen refreshes the widget
echo "설치 완료"
