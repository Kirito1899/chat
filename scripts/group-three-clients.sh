#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export MSYS2_ARG_CONV_EXCL='*'
ADB="${ADB:-/c/Users/aleks/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
"$ADB" reverse tcp:8443 tcp:8443
LOGCAT_START="$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
A_SUFFIX="${CLIENT_A_SUFFIX:-}"
B_SUFFIX="${CLIENT_B_SUFFIX:-.second}"
C_SUFFIX="${CLIENT_C_SUFFIX:-.third}"
A_PACKAGE="dev.securemessenger${A_SUFFIX}"
B_PACKAGE="dev.securemessenger${B_SUFFIX}"
C_PACKAGE="dev.securemessenger${C_SUFFIX}"
MAIN_TEST="${A_PACKAGE}.test"
SECOND_TEST="${B_PACKAGE}.test"
THIRD_TEST="${C_PACKAGE}.test"
A_EMAIL="${A_EMAIL:-phone-1795518608@example.com}"
A_USER="${A_USER:-phone_95518608}"
B_EMAIL="${B_EMAIL:-phone-1795519710@example.com}"
C_EMAIL="${C_EMAIL:-phone-1795552597@example.com}"
B_USER="${B_USER:-phone_95519710}"
C_USER="${C_USER:-phone_95552597}"
PASSWORD="${E2EE_TEST_PASSWORD:-Device-UI-Password-1}"
RUN_ID="$(date +%s)"
TITLE="Private group $RUN_ID"
WELCOME="Welcome group $RUN_ID"
REPLY="Reply group $RUN_ID"
READ_ALL="Read all $RUN_ID"
REMOVED="After removal $RUN_ID"
OFFLINE="Offline group $RUN_ID"
FILE_PLAINTEXT="Secret file payload for increment 3"
source .local/dev-env.sh
export DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/secure_messenger"
export DATABASE_USER="messenger"
export MEDIA_STORAGE_PATH="$ROOT/.local/media"

run_test() {
    local package="$1" email="$2" action="$3" member="${4:-}" message="${5:-}" expect="${6:-}" mime="${7:-}" mediaPath="${8:-}"
    local output localUser
    case "$package" in
        "$MAIN_TEST") localUser="$A_USER" ;;
        "$SECOND_TEST") localUser="$B_USER" ;;
        "$THIRD_TEST") localUser="$C_USER" ;;
        *) localUser="" ;;
    esac
    local extras=(-e class dev.securemessenger.ui.GroupConversationTest -e action "$action" -e email "$email" -e password "$PASSWORD" -e title "$TITLE" -e local_username "$localUser")
    if [[ -n "$member" ]]; then extras+=(-e member "$member"); fi
    if [[ -n "$message" ]]; then extras+=(-e message "$message"); fi
    if [[ -n "$expect" ]]; then extras+=(-e expect "$expect"); fi
    if [[ -n "$mime" ]]; then extras+=(-e mime "$mime"); fi
    if [[ -n "$mediaPath" ]]; then extras+=(-e media_path "$mediaPath"); fi
    local command="am instrument -w -r" argument escaped
    for argument in "${extras[@]}" "$package/androidx.test.runner.AndroidJUnitRunner"; do
        printf -v escaped '%q' "$argument"
        command+=" $escaped"
    done
    output=$(printf '%s\n' "$command" | "$ADB" shell)
    printf '%s\n' "$output"
    case "$output" in
        *"OK (1 test)"*) return 0 ;;
        *) printf 'Group client test failed for %s action %s\n' "$email" "$action" >&2; return 1 ;;
    esac
}

verify_ciphertext() {
    EXPECTED_TEXT="$1" MIN_QUEUE_SIZE=1 ./gradlew --quiet :devtools:verifyCiphertext
}

verify_media() {
    EXPECTED_TEXT="$1" ./gradlew --quiet :devtools:verifyMediaStorage
}

build_variant() {
    local suffix="$1" name="$2"
    if [[ -n "$suffix" ]]; then
        ./gradlew -PCLIENT_SUFFIX="$suffix" assembleDebug :app:assembleDebugAndroidTest
    else
        ./gradlew assembleDebug :app:assembleDebugAndroidTest
    fi
    cp app/build/outputs/apk/debug/app-debug.apk ".local/$name.apk"
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ".local/$name-test.apk"
}

build_variant "$A_SUFFIX" main
build_variant "$B_SUFFIX" second
build_variant "$C_SUFFIX" third

"$ADB" install -r .local/main.apk
"$ADB" install -r .local/second.apk
"$ADB" install -r .local/third.apk
"$ADB" install -r .local/main-test.apk
"$ADB" install -r .local/second-test.apk
"$ADB" install -r .local/third-test.apk

printf 'Prepare devices before encrypted invitations\n'
run_test $MAIN_TEST "$A_EMAIL" prepare
run_test $SECOND_TEST "$B_EMAIL" prepare
run_test $THIRD_TEST "$C_EMAIL" prepare
printf 'A creates group with B: %s\n' "$TITLE"
run_test $MAIN_TEST "$A_EMAIL" create "$B_USER"
printf 'A to B\n'
run_test $MAIN_TEST "$A_EMAIL" send "" "$WELCOME"
printf 'B receives and replies\n'
run_test $SECOND_TEST "$B_EMAIL" expect "" "" "$WELCOME"
run_test $SECOND_TEST "$B_EMAIL" send "" "$REPLY"
run_test $MAIN_TEST "$A_EMAIL" expect "" "" "$REPLY"
printf 'A adds C without history\n'
run_test $MAIN_TEST "$A_EMAIL" add_member "$C_USER"
run_test $THIRD_TEST "$C_EMAIL" expect_not_message "" "" "$WELCOME"

MEDIA_ROOT="/data/user/0/$A_PACKAGE/files"
push_fixture() {
    local source="$1" name="$2" expected actual
    expected=$(sha256sum "$source" | awk '{print $1}')
    base64 "$source" | "$ADB" shell "run-as $A_PACKAGE sh -c 'base64 -d > files/$name'"
    actual=$("$ADB" shell "run-as $A_PACKAGE sha256sum files/$name" | awk '{print $1}' | tr -d '\r')
    [[ "$actual" == "$expected" ]] || {
        printf 'Fixture digest differs for %s\n' "$name" >&2
        return 1
    }
}
push_fixture .local/test-image.png increment3.png
push_fixture .local/test-video.mp4 increment3.mp4
push_fixture .local/test-voice.wav increment3.wav
push_fixture .local/test-file.txt increment3.txt
IMAGE_SHA256=$(sha256sum .local/test-image.png | awk '{print $1}')
VIDEO_SHA256=$(sha256sum .local/test-video.mp4 | awk '{print $1}')
VOICE_SHA256=$(sha256sum .local/test-voice.wav | awk '{print $1}')
printf 'Encrypted photo\n'
run_test $MAIN_TEST "$A_EMAIL" send_media "" "" "" "image/png" "$MEDIA_ROOT/increment3.png"
run_test $SECOND_TEST "$B_EMAIL" expect_media "" "" "$IMAGE_SHA256" "image/png"
run_test $THIRD_TEST "$C_EMAIL" expect_media "" "" "$IMAGE_SHA256" "image/png"
printf 'Encrypted video\n'
run_test $MAIN_TEST "$A_EMAIL" send_media "" "" "" "video/mp4" "$MEDIA_ROOT/increment3.mp4"
run_test $SECOND_TEST "$B_EMAIL" expect_media "" "" "$VIDEO_SHA256" "video/mp4"
printf 'Encrypted voice\n'
run_test $MAIN_TEST "$A_EMAIL" send_media "" "" "" "audio/wav" "$MEDIA_ROOT/increment3.wav"
run_test $THIRD_TEST "$C_EMAIL" expect_media "" "" "$VOICE_SHA256" "audio/wav"
printf 'Encrypted arbitrary file\n'
run_test $MAIN_TEST "$A_EMAIL" send_media "" "" "" "text/plain" "$MEDIA_ROOT/increment3.txt"
run_test $SECOND_TEST "$B_EMAIL" verify_file "" "" "$FILE_PLAINTEXT" "text/plain"
verify_media "$FILE_PLAINTEXT"
verify_media "$TITLE"

printf 'Group read receipts\n'
run_test $MAIN_TEST "$A_EMAIL" send "" "$READ_ALL"
run_test $SECOND_TEST "$B_EMAIL" expect "" "" "$READ_ALL"
run_test $THIRD_TEST "$C_EMAIL" expect "" "" "$READ_ALL"
run_test $MAIN_TEST "$A_EMAIL" expect_status "" "" "READ"

printf 'C is removed\n'
run_test $MAIN_TEST "$A_EMAIL" remove_member "$C_USER"
run_test $MAIN_TEST "$A_EMAIL" send "" "$REMOVED"
run_test $SECOND_TEST "$B_EMAIL" expect "" "" "$REMOVED"
run_test $THIRD_TEST "$C_EMAIL" expect_removed
run_test $THIRD_TEST "$C_EMAIL" expect_not_message "" "" "$REMOVED"
run_test $THIRD_TEST "$C_EMAIL" send_removed

printf 'Offline group delivery\n'
"$ADB" shell am force-stop "$B_PACKAGE"
run_test $MAIN_TEST "$A_EMAIL" send "" "$OFFLINE"
verify_ciphertext "$OFFLINE"
run_test $SECOND_TEST "$B_EMAIL" expect "" "" "$OFFLINE"

crash_output=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START' AndroidRuntime:E DEBUG:E '*:S'")
if [[ "$crash_output" == *"FATAL EXCEPTION"* || "$crash_output" == *"Fatal signal"* ]]; then
    printf '%s\n' "$crash_output" >&2
    exit 1
fi
device_logs=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START'")
if [[ "$device_logs" == *"$TITLE"* || "$device_logs" == *"$WELCOME"* || "$device_logs" == *"$REPLY"* || "$device_logs" == *"$FILE_PLAINTEXT"* ]]; then
    printf 'Plaintext group or media content found in logcat\n' >&2
    exit 1
fi
printf 'Three-client group and media acceptance passed; no crashes or plaintext in logcat\n'
