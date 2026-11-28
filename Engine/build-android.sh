#!/bin/bash
# Builds the Rust engine for Android and generates its Kotlin bindings:
#
#   Android/app/engine-bindings/jniLibs/<abi>/libtracks_engine.so   the engine, per ABI
#   Android/app/engine-bindings/kotlin/uniffi/...                  generated Kotlin bindings
#   Android/app/engine-bindings/host/<platform>/                  the engine for this computer
#
# Needs the Android NDK (ANDROID_NDK_HOME, or the newest under $ANDROID_HOME/ndk) and cargo-ndk
# (`cargo install cargo-ndk`).
#
#   Engine/build-android.sh           release build for arm64-v8a, armeabi-v7a and x86_64
#   Engine/build-android.sh --debug   unoptimized
set -euo pipefail

cd "$(dirname "$0")"
ENGINE="$PWD"
ANDROID_DIR="$ENGINE/../Android"
PROFILE=release
CARGO_PROFILE=(--release)
for arg in "$@"; do
  case "$arg" in
    --debug) PROFILE=debug; CARGO_PROFILE=() ;;
    *) echo "✗ Unknown option $arg. The only option is --debug." >&2; exit 1 ;;
  esac
done
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ENGINE/target}"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "✗ No Android NDK found. Install one with sdkmanager \"ndk;28.2.13676358\"." >&2
  exit 1
fi

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) JNA_DIR=linux-x86-64; HOST_LIB=libtracks_engine.so ;;
  Linux-aarch64) JNA_DIR=linux-aarch64; HOST_LIB=libtracks_engine.so ;;
  Darwin-arm64) JNA_DIR=darwin-aarch64; HOST_LIB=libtracks_engine.dylib ;;
  Darwin-x86_64) JNA_DIR=darwin-x86-64; HOST_LIB=libtracks_engine.dylib ;;
  *) JNA_DIR=""; HOST_LIB="" ;;
esac

# Where earlier versions of this script put a single engine. Left there, Gradle would package
# it twice.
rm -rf "$ANDROID_DIR/app/src/main/jniLibs" "$ANDROID_DIR/app/engine-bindings/uniffi" \
  "$ANDROID_DIR"/app/src/test/resources/linux-* "$ANDROID_DIR"/app/src/test/resources/darwin-*

# armeabi-v7a is for Google TV boxes with a 32-bit userspace.
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android >/dev/null

# Clearing the whole folder also drops the per-variant engines earlier versions built there.
OUT="$ANDROID_DIR/app/engine-bindings"
rm -rf "$OUT"
mkdir -p "$OUT"

cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 31 -o "$OUT/jniLibs" \
  build -p tracks-ffi --lib "${CARGO_PROFILE[@]}" --locked

cargo run -q -p uniffi-bindgen --release --locked -- generate \
  --library "$CARGO_TARGET_DIR/aarch64-linux-android/$PROFILE/libtracks_engine.so" \
  --language kotlin --no-format --out-dir "$OUT/kotlin" >/dev/null

# The same engine for this computer, so Robolectric tests and screenshots run it on the JVM.
cargo build -p tracks-ffi --lib "${CARGO_PROFILE[@]}" --locked
if [ -n "$JNA_DIR" ]; then
  mkdir -p "$OUT/host/$JNA_DIR"
  cp "$CARGO_TARGET_DIR/$PROFILE/$HOST_LIB" "$OUT/host/$JNA_DIR/"
fi

echo "✓ TracksEngine $PROFILE built for Android (arm64-v8a, armeabi-v7a, x86_64) and this computer"
