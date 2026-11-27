#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export MSYS2_ARG_CONV_EXCL='*'
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
export PATH="$JAVA_HOME/bin:$PATH"
SSH_HOST="${SSH_HOST:-103.146.119.52}"
SSH_USER="${SSH_USER:-root}"
DOMAIN="${DOMAIN:-cbdd257d2a065caf.c37.vm.mydomain.zone}"
BASE="https://$DOMAIN:8443/"
ADB="${ADB:-/c/Users/aleks/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PASSWORD="Device-UI-Password-1"
RUN_ID="$(date +%s | tail -c 9)"
USER_A="prod_a_$RUN_ID"
USER_B="prod_b_$RUN_ID"
A_SUFFIX=".releasea$RUN_ID"
B_SUFFIX=".releaseb$RUN_ID"
A_PACKAGE="dev.securemessenger$A_SUFFIX"
B_PACKAGE="dev.securemessenger$B_SUFFIX"
CREATED_A=false
CREATED_B=false

run_remote() {
    ssh -i .local/deploy-key -o BatchMode=yes -o StrictHostKeyChecking=yes \
        -o UserKnownHostsFile="$ROOT/.local/production-hostkey" "$SSH_USER@$SSH_HOST" "$@"
}
cleanup() {
    if [[ "$CREATED_A" == "true" ]]; then run_remote "/opt/secure-messenger/deploy/lock-test-account.sh $USER_A" || true; fi
    if [[ "$CREATED_B" == "true" ]]; then run_remote "/opt/secure-messenger/deploy/lock-test-account.sh $USER_B" || true; fi
}
trap cleanup EXIT

EMAIL_A=$(run_remote "/opt/secure-messenger/deploy/create-test-account.sh $USER_A '$PASSWORD'")
CREATED_A=true
EMAIL_B=$(run_remote "/opt/secure-messenger/deploy/create-test-account.sh $USER_B '$PASSWORD'")
CREATED_B=true
EMAIL_A="${EMAIL_A//$'\r'/}"
EMAIL_B="${EMAIL_B//$'\r'/}"

CLIENT_SUFFIX="$A_SUFFIX" API_BASE_URL="$BASE" bash scripts/build-release.sh
cp app/build/outputs/apk/release/app-release.apk .local/production-a.apk
CLIENT_SUFFIX="$B_SUFFIX" API_BASE_URL="$BASE" bash scripts/build-release.sh
cp app/build/outputs/apk/release/app-release.apk .local/production-b.apk
"$ADB" reverse tcp:8443 tcp:8443
"$ADB" install -r .local/production-a.apk
"$ADB" install -r .local/production-b.apk
export ADB="$(cygpath -m "$ADB")"
LOGCAT_START="$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"

python scripts/android-ui.py login "$A_PACKAGE" "$EMAIL_A" "$USER_A" "$PASSWORD"
python scripts/android-ui.py login "$B_PACKAGE" "$EMAIL_B" "$USER_B" "$PASSWORD"
python scripts/android-ui.py open_chat "$A_PACKAGE" "$USER_B"
python scripts/android-ui.py send "Prod A $RUN_ID"
python scripts/android-ui.py notification "$B_PACKAGE" "$USER_A" "Prod A $RUN_ID"
python scripts/android-ui.py send "Prod B $RUN_ID"
python scripts/android-ui.py open_chat "$A_PACKAGE" "$USER_B"
python scripts/android-ui.py expect "Prod B $RUN_ID"
python scripts/android-ui.py safety

OFFLINE="Offline $RUN_ID"
python scripts/android-ui.py list "$B_PACKAGE"
python scripts/android-ui.py stop "$B_PACKAGE"
python scripts/android-ui.py open_chat "$A_PACKAGE" "$USER_B"
python scripts/android-ui.py send "$OFFLINE"
printf -v EXPECTED '%q' "$OFFLINE"
run_remote "EXPECTED_TEXT=$EXPECTED /opt/secure-messenger/deploy/verify-ciphertext.sh"
python scripts/android-ui.py login "$B_PACKAGE" "$EMAIL_B" "$USER_B" "$PASSWORD"
python scripts/android-ui.py show_conversation "$B_PACKAGE" "$USER_A" "$OFFLINE" 1
python scripts/android-ui.py stop "$B_PACKAGE"
python scripts/android-ui.py open_conversation "$B_PACKAGE" "$USER_A" "$OFFLINE" 1
python scripts/android-ui.py expect "$OFFLINE"

crash_output=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START' AndroidRuntime:E DEBUG:E '*:S'")
if [[ "$crash_output" == *"FATAL EXCEPTION"* || "$crash_output" == *"Fatal signal"* ]]; then
    printf '%s\n' "$crash_output" >&2
    exit 1
fi
device_logs=$("$ADB" shell "logcat -d -v brief -T '$LOGCAT_START'")
if [[ "$device_logs" == *"Prod A $RUN_ID"* || "$device_logs" == *"Prod B $RUN_ID"* || "$device_logs" == *"$OFFLINE"* ]]; then
    printf 'Plaintext production test content found in logcat\n' >&2
    exit 1
fi
printf 'Production release E2EE, offline delivery and safety number passed; no crashes or plaintext in logcat\n'
