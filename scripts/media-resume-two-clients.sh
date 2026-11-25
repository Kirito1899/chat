#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export MSYS2_ARG_CONV_EXCL='*'
ADB="${ADB:-/c/Users/aleks/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
"$ADB" reverse tcp:8443 tcp:8443
LOGCAT_START="$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
A_PACKAGE=dev.securemessenger.fresha
B_PACKAGE=dev.securemessenger.freshb
A_EMAIL=phone-1795585309@example.com
B_EMAIL=phone-1795585316@example.com
A_USER=phone_95585309
B_USER=phone_95585316
PASSWORD=Device-UI-Password-1
PENDING_MEDIA="Pending media $(date +%s)"
API_ARGS=(-PAPI_BASE_URL="${API_BASE_URL:-https://127.0.0.1:8443/}")
if [[ -n "${API_FALLBACK_URL:-}" ]]; then API_ARGS+=(-PAPI_FALLBACK_URL="$API_FALLBACK_URL"); fi

build_variant() {
    ./gradlew "${API_ARGS[@]}" -PCLIENT_SUFFIX="$1" assembleDebug :app:assembleDebugAndroidTest
    cp app/build/outputs/apk/debug/app-debug.apk ".local/$2.apk"
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ".local/$2-test.apk"
}

run_mode() {
    local package="$1" mode="$2" email="$3" peer="$4" expected="${5:-}" mediaPath="${6:-}"
    local extras=(-e class dev.securemessenger.data.sync.SyncFallbackTest -e mode "$mode" -e email "$email" -e password "$PASSWORD" -e peer_username "$peer" -e expect "$expected")
    if [[ -n "$mediaPath" ]]; then extras+=(-e media_path "$mediaPath"); fi
    local command="am instrument -w -r" argument escaped output
    for argument in "${extras[@]}" "$package.test/androidx.test.runner.AndroidJUnitRunner"; do
        printf -v escaped '%q' "$argument"
        command+=" $escaped"
    done
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    "$ADB" shell wm dismiss-keyguard
    output=$(printf '%s\n' "$command" | "$ADB" shell)
    printf '%s\n' "$output"
    case "$output" in
        *"OK (1 test)"*) return 0 ;;
        *) printf 'Media resume test failed: %s %s\n' "$package" "$mode" >&2; return 1 ;;
    esac
}

build_variant .fresha resume-a
build_variant .freshb resume-b
"$ADB" install -r .local/resume-a.apk
"$ADB" install -r .local/resume-b.apk
"$ADB" install -r .local/resume-a-test.apk
"$ADB" install -r .local/resume-b-test.apk
"$ADB" shell pm grant "$A_PACKAGE" android.permission.POST_NOTIFICATIONS
"$ADB" shell pm grant "$B_PACKAGE" android.permission.POST_NOTIFICATIONS
run_mode "$B_PACKAGE" prepare "$B_EMAIL" "$A_USER"
printf '%s' "$PENDING_MEDIA" | base64 | "$ADB" shell "run-as $A_PACKAGE sh -c 'base64 -d > files/pending-media.txt'"
run_mode "$A_PACKAGE" prepare_pending_media "$A_EMAIL" "$B_USER" "$PENDING_MEDIA" "/data/user/0/$A_PACKAGE/files/pending-media.txt"
"$ADB" shell input keyevent KEYCODE_HOME
"$ADB" shell am kill "$A_PACKAGE"
"$ADB" shell cmd jobscheduler run -f "$A_PACKAGE" 704173
run_mode "$A_PACKAGE" verify_pending_media "$A_EMAIL" "$B_USER" "$PENDING_MEDIA"
"$ADB" shell cmd jobscheduler run -f "$A_PACKAGE" 704173
run_mode "$A_PACKAGE" verify_pending_media "$A_EMAIL" "$B_USER" "$PENDING_MEDIA"
run_mode "$B_PACKAGE" expect_direct_media "$B_EMAIL" "$A_USER" "$PENDING_MEDIA"
printf 'Pending media resumed after process death without duplicates\n'

crash_output=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START' AndroidRuntime:E DEBUG:E '*:S'")
if [[ "$crash_output" == *"FATAL EXCEPTION"* || "$crash_output" == *"Fatal signal"* ]]; then
    printf '%s\n' "$crash_output" >&2
    exit 1
fi
device_logs=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START'")
if [[ "$device_logs" == *"$PENDING_MEDIA"* ]]; then
    printf 'Plaintext pending media found in logcat\n' >&2
    exit 1
fi
printf 'Media resume acceptance passed; no crashes or plaintext in logcat\n'
