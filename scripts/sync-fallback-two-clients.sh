#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export MSYS2_ARG_CONV_EXCL='*'
ADB="${ADB:-/c/Users/aleks/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
LOGCAT_START="$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
A_PACKAGE=dev.securemessenger.fresha
B_PACKAGE=dev.securemessenger.freshb
A_EMAIL=phone-1795585309@example.com
B_EMAIL=phone-1795585316@example.com
A_USER=phone_95585309
B_USER=phone_95585316
PASSWORD=Device-UI-Password-1
RUN_ID="$(date +%s)"
WSS_MESSAGE="WSS event $RUN_ID"
OFFLINE_MESSAGE="Offline fallback $RUN_ID"
API_ARGS=(-PAPI_BASE_URL="${API_BASE_URL:-https://127.0.0.1:8443/}")
if [[ -n "${API_FALLBACK_URL:-}" ]]; then API_ARGS+=(-PAPI_FALLBACK_URL="$API_FALLBACK_URL"); fi

build_variant() {
    ./gradlew "${API_ARGS[@]}" -PCLIENT_SUFFIX="$1" assembleDebug :app:assembleDebugAndroidTest
    cp app/build/outputs/apk/debug/app-debug.apk ".local/$2.apk"
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ".local/$2-test.apk"
}

run_mode() {
    local package="$1" mode="$2" email="$3" peer="$4" expected="${5:-}"
    local extras=(-e class dev.securemessenger.data.sync.SyncFallbackTest -e mode "$mode" -e email "$email" -e password "$PASSWORD" -e peer_username "$peer" -e expect "$expected")
    local command="am instrument -w -r" argument escaped output
    for argument in "${extras[@]}" "$package.test/androidx.test.runner.AndroidJUnitRunner"; do
        printf -v escaped '%q' "$argument"
        command+=" $escaped"
    done
    output=$(printf '%s\n' "$command" | "$ADB" shell)
    printf '%s\n' "$output"
    case "$output" in
        *"OK (1 test)"*) return 0 ;;
        *) printf 'Sync fallback test failed: %s %s\n' "$package" "$mode" >&2; return 1 ;;
    esac
}

build_variant .fresha sync-a
build_variant .freshb sync-b
"$ADB" install -r .local/sync-a.apk
"$ADB" install -r .local/sync-b.apk
"$ADB" install -r .local/sync-a-test.apk
"$ADB" install -r .local/sync-b-test.apk
"$ADB" shell pm grant "$A_PACKAGE" android.permission.POST_NOTIFICATIONS
"$ADB" shell pm grant "$B_PACKAGE" android.permission.POST_NOTIFICATIONS
run_mode "$B_PACKAGE" prepare "$B_EMAIL" "$A_USER"

run_mode "$B_PACKAGE" wss_event "$B_EMAIL" "$A_USER" > .local/sync-wss.log &
WSS_PID=$!
for _ in {1..30}; do
    if "$ADB" shell "run-as $B_PACKAGE test -f files/wss-ready" 2>/dev/null; then break; fi
    sleep 1
done
"$ADB" shell "run-as $B_PACKAGE test -f files/wss-ready"
run_mode "$A_PACKAGE" send_direct "$A_EMAIL" "$B_USER" "$WSS_MESSAGE"
wait "$WSS_PID"
printf 'Real server WSS event passed\n'

"$ADB" shell input keyevent KEYCODE_HOME
"$ADB" shell am kill "$B_PACKAGE"
run_mode "$A_PACKAGE" send_direct "$A_EMAIL" "$B_USER" "$OFFLINE_MESSAGE"
"$ADB" shell cmd jobscheduler run -f "$B_PACKAGE" 704173
run_mode "$B_PACKAGE" offline_notification "$B_EMAIL" "$A_USER" "$OFFLINE_MESSAGE"
printf 'Offline JobScheduler delivery and generic notification passed\n'

crash_output=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START' AndroidRuntime:E DEBUG:E '*:S'")
if [[ "$crash_output" == *"FATAL EXCEPTION"* || "$crash_output" == *"Fatal signal"* ]]; then
    printf '%s\n' "$crash_output" >&2
    exit 1
fi
device_logs=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START'")
if [[ "$device_logs" == *"$WSS_MESSAGE"* || "$device_logs" == *"$OFFLINE_MESSAGE"* ]]; then
    printf 'Plaintext fallback content found in logcat\n' >&2
    exit 1
fi
printf 'WSS and offline fallback acceptance passed; no crashes or plaintext in logcat\n'
