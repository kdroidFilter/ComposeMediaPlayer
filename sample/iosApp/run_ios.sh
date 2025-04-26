#!/usr/bin/env bash
set -euo pipefail

# Script to compile and launch the iOS application in a simulator
# Usage: ./run_ios.sh [SIMULATOR_UDID]
#
# If no UDID is provided, the script will attempt to use an available iPhone simulator.
# To list available simulators: xcrun simctl list devices available

### — Parameters —
SCHEME="iosApp"                                   # Scheme name
CONFIG="Debug"
# If UDID is provided, use it; otherwise find the latest iOS version and use an iPhone from that version
if [[ -n "${1:-}" ]]; then
  UDID="$1"
else
  # Get the list of available devices
  DEVICES_LIST=$(xcrun simctl list devices available)

  # Find the latest iOS version by extracting all iOS version numbers and sorting them
  LATEST_IOS_VERSION=$(echo "$DEVICES_LIST" | grep -E -e "-- iOS [0-9]+\.[0-9]+ --" | 
                       sed -E 's/.*-- iOS ([0-9]+\.[0-9]+) --.*/\1/' | 
                       sort -t. -k1,1n -k2,2n | 
                       tail -1)

  echo "🔍 Latest iOS version found: $LATEST_IOS_VERSION"

  # Find the first iPhone in the latest iOS version section
  UDID=$(echo "$DEVICES_LIST" | 
         awk -v version="-- iOS $LATEST_IOS_VERSION --" 'BEGIN {found=0} 
              $0 ~ version {found=1; next} 
              /-- iOS/ {found=0} 
              found && /iPhone/ {print; exit}' | 
         sed -E 's/.*\(([A-Z0-9-]+)\).*/\1/' | 
         head -1)

  # If no iPhone is found in the latest iOS version, fall back to any simulator
  if [[ -z "$UDID" ]]; then
    echo "⚠️ No iPhone found for iOS $LATEST_IOS_VERSION, falling back to any available simulator"
    UDID=$(echo "$DEVICES_LIST" | grep -E '\([A-Z0-9-]+\)' | head -1 | sed -E 's/.*\(([A-Z0-9-]+)\).*/\1/')
  fi
fi

# Check if a simulator was found
if [[ -z "$UDID" ]]; then
  echo "❌ No available iOS simulator found. Please create one in Xcode."
  echo "   You can also specify a UDID manually as the first argument of the script:"
  echo "   ./run_ios.sh SIMULATOR_UDID"
  echo ""
  echo "   To list available simulators:"
  echo "   xcrun simctl list devices available"
  exit 1
fi

echo "🔍 Using simulator with UDID: $UDID"
DERIVED_DATA="$(pwd)/build"
BUNDLE_ID="sample.app.iosApp"

### — Detecting project/workspace in current directory —
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WORKSPACE=$(find "$ROOT_DIR" -maxdepth 1 -name "*.xcworkspace" | head -n1)
XCODEPROJ=$(find "$ROOT_DIR" -maxdepth 1 -name "*.xcodeproj"    | head -n1)

if [[ -n "$WORKSPACE" ]]; then
  BUILD_BASE=(xcodebuild -workspace "$WORKSPACE")
elif [[ -n "$XCODEPROJ" ]]; then
  BUILD_BASE=(xcodebuild -project "$XCODEPROJ")
else
  echo "❌ No .xcworkspace or .xcodeproj found in $ROOT_DIR"
  exit 1
fi

### — Compilation —
echo "⏳ Compiling for simulator..."
BUILD_CMD=("${BUILD_BASE[@]}"
           -scheme "$SCHEME"
           -configuration "$CONFIG"
           -sdk iphonesimulator
           -destination "id=$UDID"
           -derivedDataPath "$DERIVED_DATA"
           build)

if command -v xcpretty &>/dev/null; then
  "${BUILD_CMD[@]}" | xcpretty
else
  "${BUILD_CMD[@]}"
fi

echo "🔍 Searching for the application in the build folder..."
APP_DIR="$DERIVED_DATA/Build/Products/${CONFIG}-iphonesimulator"
# Search for the .app application in the build folder
APP_PATH=$(find "$APP_DIR" -maxdepth 1 -name "*.app" | head -n1)
[[ -n "$APP_PATH" ]] || { echo "❌ No .app application found in $APP_DIR"; exit 1; }
echo "✅ Application found: $APP_PATH"

# Extract the bundle ID from the application
echo "🔍 Extracting bundle ID..."
EXTRACTED_BUNDLE_ID=$(defaults read "$APP_PATH/Info" CFBundleIdentifier 2>/dev/null)
if [[ -n "$EXTRACTED_BUNDLE_ID" ]]; then
  echo "✅ Bundle ID extracted: $EXTRACTED_BUNDLE_ID"
  BUNDLE_ID="$EXTRACTED_BUNDLE_ID"
else
  echo "⚠️ Unable to extract bundle ID, using default value: $BUNDLE_ID"
fi

### — Simulator, installation, launch —
echo "🚀 Booting simulator..."
xcrun simctl boot "$UDID" 2>/dev/null || true        # idempotent

# Open the Simulator.app application to display the simulator window
echo "🖥️  Opening Simulator application..."
open -a Simulator

# Wait for the simulator to be fully booted
echo "⏳ Waiting for simulator to fully boot..."
MAX_WAIT=30
WAIT_COUNT=0
while ! xcrun simctl list devices | grep "$UDID" | grep -q "(Booted)"; do
  sleep 1
  WAIT_COUNT=$((WAIT_COUNT + 1))
  if [[ $WAIT_COUNT -ge $MAX_WAIT ]]; then
    echo "❌ Timeout waiting for simulator to boot"
    exit 1
  fi
  echo -n "."
done
echo ""
echo "✅ Simulator started"

echo "📲 Installing the app..."
xcrun simctl install booted "$APP_PATH"

echo "▶️  Launching with logs..."
echo "   Bundle ID: $BUNDLE_ID"
xcrun simctl launch --console booted "$BUNDLE_ID"
