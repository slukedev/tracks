#!/bin/bash
# Builds the Rust engine for Android and generates its Kotlin bindings:
#
#   Android/app/src/main/jniLibs/<abi>/libtracks_engine.so   the engine, per ABI
#   Android/app/engine-bindings/uniffi/...                  generated Kotlin bindings
#
# The Gradle build runs this before compiling, so it rarely needs running by hand. Needs the
# Android NDK (ANDROID_NDK_HOME, or the newest under $ANDROID_HOME/ndk) and cargo-ndk
# (`cargo install cargo-ndk`).
#
#   Engine/build-android.sh            release build for arm64-v8a and x86_64
#   Engine/build-android.sh --debug    unoptimized
set -euo pipefail

cd "$(dirname "$0")"
ENGINE="$PWD"
ANDROID_DIR="$ENGINE/../Android"
PROFILE=release
CARGO_PROFILE=(--release)
if [ "${1:-}" = "--debug" ]; then
  PROFILE=debug
  CARGO_PROFILE=()
fi
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

rustup target add aarch64-linux-android x86_64-linux-android >/dev/null
cargo ndk -t arm64-v8a -t x86_64 --platform 31 -o "$ANDROID_DIR/app/src/main/jniLibs" \
  build -p tracks-ffi --lib "${CARGO_PROFILE[@]}" --locked

OUT="$ANDROID_DIR/app/engine-bindings"
rm -rf "$OUT"
cargo run -q -p uniffi-bindgen --release --locked -- generate \
  --library "$CARGO_TARGET_DIR/aarch64-linux-android/$PROFILE/libtracks_engine.so" \
  --language kotlin --no-format --out-dir "$OUT" >/dev/null

# The same engine for this computer, so Robolectric tests and screenshots run it on the JVM.
cargo build -p tracks-ffi --lib "${CARGO_PROFILE[@]}" --locked
HOST_LIB="$ANDROID_DIR/app/src/test/resources"
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) JNA_DIR=linux-x86-64; LIB=libtracks_engine.so ;;
  Linux-aarch64) JNA_DIR=linux-aarch64; LIB=libtracks_engine.so ;;
  Darwin-arm64) JNA_DIR=darwin-aarch64; LIB=libtracks_engine.dylib ;;
  Darwin-x86_64) JNA_DIR=darwin-x86-64; LIB=libtracks_engine.dylib ;;
  *) JNA_DIR=""; LIB="" ;;
esac
if [ -n "$JNA_DIR" ]; then
  mkdir -p "$HOST_LIB/$JNA_DIR"
  cp "$CARGO_TARGET_DIR/$PROFILE/$LIB" "$HOST_LIB/$JNA_DIR/"
fi

echo "✓ TracksEngine $PROFILE built for Android (arm64-v8a, x86_64) and this computer"
