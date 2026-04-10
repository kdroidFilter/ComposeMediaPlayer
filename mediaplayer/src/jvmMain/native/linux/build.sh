#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"

echo "=== Building Linux NativeVideoPlayer ==="

# Clean and create build directory
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

cd "$BUILD_DIR"
cmake "$SCRIPT_DIR" -DCMAKE_BUILD_TYPE=Release
cmake --build . --parallel

echo "=== Build completed ==="

# Show output location
ARCH=$(uname -m)
case "$ARCH" in
    x86_64|amd64)
        echo "Output: $SCRIPT_DIR/../../resources/linux-x86-64/libNativeVideoPlayer.so"
        ;;
    aarch64|arm64)
        echo "Output: $SCRIPT_DIR/../../resources/linux-aarch64/libNativeVideoPlayer.so"
        ;;
    *)
        echo "Output: $SCRIPT_DIR/../../resources/linux-$ARCH/libNativeVideoPlayer.so"
        ;;
esac
