#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${NATIVE_LIBS_OUTPUT_DIR:-$SCRIPT_DIR/../../resources/composemediaplayer/native}"

SWIFT_SOURCE="$SCRIPT_DIR/NativeVideoPlayer.swift"
JNI_BRIDGE="$SCRIPT_DIR/jni_bridge.c"

echo "=== Building macOS NativeVideoPlayer ==="
echo "Output dir: $OUTPUT_DIR"

# Resolve JDK include paths (required to compile jni_bridge.c)
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo '')}"
if [ -z "$JAVA_HOME" ]; then
    echo "ERROR: JAVA_HOME is not set and could not be detected automatically."
    exit 1
fi
JNI_INCLUDES="-I${JAVA_HOME}/include -I${JAVA_HOME}/include/darwin"

# Output directories
ARM64_DIR="$OUTPUT_DIR/darwin-aarch64"
X64_DIR="$OUTPUT_DIR/darwin-x86-64"

mkdir -p "$ARM64_DIR" "$X64_DIR"

build_arch() {
    local ARCH="$1"
    local TARGET="${ARCH}-apple-macosx14.0"
    local OUTPUT_DIR="$2"
    local BRIDGE_OBJ="/tmp/jni_bridge_${ARCH}.o"

    echo "=== Compiling JNI bridge for ${ARCH} ==="
    clang -c -arch "$ARCH" -target "$TARGET" \
        $JNI_INCLUDES \
        "$JNI_BRIDGE" -o "$BRIDGE_OBJ"

    echo "=== Building NativeVideoPlayer dylib for ${ARCH} ==="
    swiftc -emit-library -emit-module -module-name NativeVideoPlayer \
        -target "$TARGET" \
        -o "$OUTPUT_DIR/libNativeVideoPlayer.dylib" \
        "$SWIFT_SOURCE" \
        "$BRIDGE_OBJ" \
        -O -whole-module-optimization

    # Clean up Swift build artifacts
    rm -f "$OUTPUT_DIR"/NativeVideoPlayer.abi.json \
          "$OUTPUT_DIR"/NativeVideoPlayer.swiftdoc \
          "$OUTPUT_DIR"/NativeVideoPlayer.swiftmodule \
          "$OUTPUT_DIR"/NativeVideoPlayer.swiftsourceinfo
    rm -f "$BRIDGE_OBJ"
}

build_arch "arm64"   "$ARM64_DIR"
build_arch "x86_64"  "$X64_DIR"

echo "=== Build completed ==="
echo "arm64:  $ARM64_DIR/libNativeVideoPlayer.dylib"
echo "x86_64: $X64_DIR/libNativeVideoPlayer.dylib"
