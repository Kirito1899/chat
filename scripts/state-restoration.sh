#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export MSYS2_ARG_CONV_EXCL='*'
ADB="${ADB:-/c/Users/aleks/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
"$ADB" reverse tcp:8443 tcp:8443
LOGCAT_START="$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
PACKAGE=dev.securemessenger.fresha
./gradlew -PCLIENT_SUFFIX=.fresha assembleDebug :app:assembleDebugAndroidTest
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

verify_restored() {
    local xml
    for _ in {1..30}; do
        if ! xml=$("$ADB" shell 'uiautomator dump /sdcard/securemessenger-state.xml >/dev/null && cat /sdcard/securemessenger-state.xml'); then
            sleep 1
            continue
        fi
        if [[ "$xml" == *"phone_95585316"* && "$xml" == *"Черновик после пересоздания"* ]]; then
            return 0
        fi
        sleep 1
    done
    printf 'Selected chat or draft was not restored in the real activity\n' >&2
    return 1
}

"$ADB" shell "am instrument -w -r -e class dev.securemessenger.ui.RealAccountFlowTest -e email phone-1795585309@example.com -e password Device-UI-Password-1 -e username phone_95585316 -e wait_for_kill true $PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" > .local/state-first.log 2>&1 &
FIRST_PID=$!
for _ in {1..45}; do
    if "$ADB" shell "run-as $PACKAGE test -f files/state-ready" 2>/dev/null; then break; fi
    if ! kill -0 "$FIRST_PID" 2>/dev/null; then
        printf 'Initial state setup failed before process death\n' >&2
        exit 1
    fi
    sleep 1
done
"$ADB" shell "run-as $PACKAGE test -f files/state-ready"
kill_package_processes() {
    local package="$1" pid
    for pid in $("$ADB" shell "run-as $package ps -A -o PID,NAME" | awk -v package="$package" '$2 == package { print $1 }'); do
        "$ADB" shell "run-as $package kill $pid"
    done
}
"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
for _ in {1..30}; do
    if "$ADB" shell 'dumpsys activity activities' | awk '/dev.securemessenger.fresha\/dev.securemessenger.MainActivity/ { activity=1 } /state=STOPPED/ { stopped=1 } END { exit !(activity && stopped) }'; then
        break
    fi
    sleep 1
done
kill_package_processes "$PACKAGE"
kill_package_processes "$PACKAGE.test"
wait "$FIRST_PID" || true
"$ADB" shell am start -n "$PACKAGE/dev.securemessenger.MainActivity" >/dev/null
verify_restored

crash_output=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START' AndroidRuntime:E DEBUG:E '*:S'")
if [[ "$crash_output" == *"FATAL EXCEPTION"* || "$crash_output" == *"Fatal signal"* ]]; then
    printf '%s\n' "$crash_output" >&2
    exit 1
fi
device_logs=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START'")
if [[ "$device_logs" == *"Черновик после пересоздания"* ]]; then
    printf 'Draft plaintext found in logcat\n' >&2
    exit 1
fi
printf 'Draft and selected chat survived process death; no crashes or plaintext in logcat\n'
