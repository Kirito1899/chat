#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ADB="${ADB:-/c/Users/aleks/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
"$ADB" reverse tcp:8443 tcp:8443
LOGCAT_START="$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
A_EMAIL="${A_EMAIL:-phone-1795518608@example.com}"
A_USER="${A_USER:-phone_95518608}"
B_EMAIL="${B_EMAIL:-phone-1795519710@example.com}"
B_USER="${B_USER:-phone_95519710}"
PASSWORD="${E2EE_TEST_PASSWORD:-Device-UI-Password-1}"
RUN_ID="$(date +%s)"
HELLO_MESSAGE="Hello E2EE $RUN_ID"
REPLY_MESSAGE="Reply E2EE $RUN_ID"
OFFLINE_MESSAGE="Offline E2EE $RUN_ID"
source .local/dev-env.sh
export DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/secure_messenger"
export DATABASE_USER="messenger"

verify_ciphertext() {
    EXPECTED_TEXT="$1" MIN_QUEUE_SIZE=1 ./gradlew --quiet :devtools:verifyCiphertext
}

run_test() {
    local package="$1"
    local email="$2"
    local remote="$3"
    local message="${4:-}"
    local expect="${5:-}"
    local reply="${6:-}"
    local reviewSafety="${7:-}"
    local output
    local extras=(-e class dev.securemessenger.ui.E2eeConversationTest -e email "$email" -e password "$PASSWORD" -e remote "$remote")
    if [[ -n "$message" ]]; then extras+=(-e message "$message"); fi
    if [[ -n "$expect" ]]; then extras+=(-e expect "$expect"); fi
    if [[ -n "$reply" ]]; then extras+=(-e reply "$reply"); fi
    if [[ "$reviewSafety" == "true" ]]; then extras+=(-e review_safety true); fi
    local command="am instrument -w -r"
    local argument escaped
    for argument in "${extras[@]}" "$package/androidx.test.runner.AndroidJUnitRunner"; do
        printf -v escaped '%q' "$argument"
        command+=" $escaped"
    done
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    "$ADB" shell wm dismiss-keyguard
    output=$(printf '%s\n' "$command" | "$ADB" shell)
    printf '%s\n' "$output"
    case "$output" in
        *"OK (1 test)"*) return 0 ;;
        *) printf 'Client test failed for %s\n' "$email" >&2; return 1 ;;
    esac
}

./gradlew assembleDebug :app:assembleDebugAndroidTest
cp app/build/outputs/apk/debug/app-debug.apk .local/main.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk .local/main-test.apk
./gradlew -PCLIENT_SUFFIX=.second assembleDebug :app:assembleDebugAndroidTest
cp app/build/outputs/apk/debug/app-debug.apk .local/second.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk .local/second-test.apk

"$ADB" install -r .local/main.apk
"$ADB" install -r .local/second.apk
"$ADB" install -r .local/main-test.apk
"$ADB" install -r .local/second-test.apk

printf 'Prepare A\n'
run_test dev.securemessenger.test "$A_EMAIL" "$B_USER"
printf 'Prepare B\n'
run_test dev.securemessenger.second.test "$B_EMAIL" "$A_USER"
printf 'A to B\n'
run_test dev.securemessenger.test "$A_EMAIL" "$B_USER" "$HELLO_MESSAGE"
verify_ciphertext "$HELLO_MESSAGE"
printf 'B receives and replies\n'
run_test dev.securemessenger.second.test "$B_EMAIL" "$A_USER" "" "$HELLO_MESSAGE" "$REPLY_MESSAGE"
verify_ciphertext "$REPLY_MESSAGE"
printf 'A receives reply\n'
run_test dev.securemessenger.test "$A_EMAIL" "$B_USER" "" "$REPLY_MESSAGE"
printf 'A reviews safety number\n'
run_test dev.securemessenger.test "$A_EMAIL" "$B_USER" "" "" "" true

printf 'B offline\n'
"$ADB" shell am force-stop dev.securemessenger.second
printf 'A sends offline message\n'
run_test dev.securemessenger.test "$A_EMAIL" "$B_USER" "$OFFLINE_MESSAGE"
verify_ciphertext "$OFFLINE_MESSAGE"
printf 'B receives after restart\n'
run_test dev.securemessenger.second.test "$B_EMAIL" "$A_USER" "" "$OFFLINE_MESSAGE"

crash_output=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START' AndroidRuntime:E DEBUG:E '*:S'")
if [[ "$crash_output" == *"FATAL EXCEPTION"* || "$crash_output" == *"Fatal signal"* ]]; then
    printf '%s\n' "$crash_output" >&2
    exit 1
fi
device_logs=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START'")
if [[ "$device_logs" == *"$HELLO_MESSAGE"* || "$device_logs" == *"$REPLY_MESSAGE"* || "$device_logs" == *"$OFFLINE_MESSAGE"* ]]; then
    printf 'Plaintext message content found in logcat\n' >&2
    exit 1
fi
printf 'No application crashes or plaintext messages in logcat\n'
