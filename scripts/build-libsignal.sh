#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/.local/libsignal/libsignal-0.108.0"
SDK="$(cygpath -m "$ROOT")/.local/android-sdk"
export RUSTUP_HOME="$(cygpath -m "$ROOT")/.local/rustup"
export CARGO_HOME="$(cygpath -m "$ROOT")/.local/cargo"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_NDK_HOME="$SDK/ndk/28.0.13004108"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
export PATH="$ROOT/.local/winlibs/mingw64/bin:$ROOT/.local/bin:$ROOT/.local/cargo/bin:$ROOT/.local/android-sdk/cmake/3.22.1/bin:$PATH"

if [[ ! -d "$SOURCE" ]]; then
    echo "Official libsignal v0.108.0 source is missing" >&2
    exit 1
fi
printf 'sdk.dir=%s\n' "$SDK" > "$SOURCE/java/local.properties"
cd "$SOURCE/java"
./gradlew --no-daemon -PandroidArchs=aarch64,x86_64 :client:publishToMavenLocal :android:publishToMavenLocal
