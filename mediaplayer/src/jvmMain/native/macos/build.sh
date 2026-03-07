#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESOURCES_DIR="$SCRIPT_DIR/../../resources"

SWIFT_SOURCE="$SCRIPT_DIR/NativeVideoPlayer.swift"

# Output directories (JNA resource path convention)
ARM64_DIR="$RESOURCES_DIR/darwin-aarch64"
X64_DIR="$RESOURCES_DIR/darwin-x86-64"

mkdir -p "$ARM64_DIR" "$X64_DIR"

echo "=== Building NativeVideoPlayer for macOS arm64 ==="
swiftc -emit-library -emit-module -module-name NativeVideoPlayer \
    -target arm64-apple-macosx14.0 \
    -o "$ARM64_DIR/libNativeVideoPlayer.dylib" \
    "$SWIFT_SOURCE" \
    -O -whole-module-optimization

echo "=== Building NativeVideoPlayer for macOS x86_64 ==="
swiftc -emit-library -emit-module -module-name NativeVideoPlayer \
    -target x86_64-apple-macosx14.0 \
    -o "$X64_DIR/libNativeVideoPlayer.dylib" \
    "$SWIFT_SOURCE" \
    -O -whole-module-optimization

# Clean up swift build artifacts
rm -f "$ARM64_DIR"/NativeVideoPlayer.abi.json "$ARM64_DIR"/NativeVideoPlayer.swiftdoc \
      "$ARM64_DIR"/NativeVideoPlayer.swiftmodule "$ARM64_DIR"/NativeVideoPlayer.swiftsourceinfo
rm -f "$X64_DIR"/NativeVideoPlayer.abi.json "$X64_DIR"/NativeVideoPlayer.swiftdoc \
      "$X64_DIR"/NativeVideoPlayer.swiftmodule "$X64_DIR"/NativeVideoPlayer.swiftsourceinfo

echo "=== Build completed ==="
echo "arm64: $ARM64_DIR/libNativeVideoPlayer.dylib"
echo "x86_64: $X64_DIR/libNativeVideoPlayer.dylib"
