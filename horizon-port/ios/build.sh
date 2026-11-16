#!/bin/bash
# Build the unsigned development IPA for the Horizon iOS shell.
#
# Inputs:
#   HORIZON_DIR       checkout of horizon-rust/horizon with horizon-ios added
#   CARGO_TARGET_DIR  cargo target directory (default: $HORIZON_DIR/target)
#   BUILD_NUMBER      CFBundleVersion (default: UTC timestamp)
# Output:
#   dist/HorizonIOS-dev-unsigned.ipa
set -euxo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HORIZON_DIR="${HORIZON_DIR:-/tmp/horizon}"
TARGET_DIR="${CARGO_TARGET_DIR:-$HORIZON_DIR/target}"
BUILD_NUMBER="${BUILD_NUMBER:-$(date -u +%Y%m%d%H%M)}"
APP_NAME="HorizonIOS"
SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
OUT="$ROOT/build"
APP="$OUT/$APP_NAME.app"
DIST="$ROOT/dist"

rm -rf "$OUT" "$DIST"
mkdir -p "$APP" "$DIST"

STATIC_LIB="$TARGET_DIR/aarch64-apple-ios/release/libhorizon_ios.a"
test -f "$STATIC_LIB"

xcrun --sdk iphoneos clang \
    -arch arm64 \
    -mios-version-min=15.0 \
    -isysroot "$SDK" \
    -fobjc-arc \
    -O2 \
    -framework UIKit \
    -framework Foundation \
    "$ROOT/ios/main.m" \
    "$STATIC_LIB" \
    -o "$APP/$APP_NAME"

cp "$ROOT/ios/Info.plist" "$APP/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUMBER" "$APP/Info.plist"

# Ad-hoc signature only: the IPA stays re-signable by Sideloadly/AltStore.
codesign --force --sign - --timestamp=none "$APP/$APP_NAME"
codesign --force --sign - --timestamp=none "$APP"

mkdir -p "$OUT/Payload"
cp -R "$APP" "$OUT/Payload/"
(cd "$OUT" && zip -qry "$DIST/HorizonIOS-dev-unsigned.ipa" Payload)

echo "--- artifact ---"
ls -lh "$DIST/HorizonIOS-dev-unsigned.ipa"
shasum -a 256 "$DIST/HorizonIOS-dev-unsigned.ipa"
echo "--- app ---"
file "$APP/$APP_NAME"
plutil -p "$APP/Info.plist" | head -40
unzip -l "$DIST/HorizonIOS-dev-unsigned.ipa" | head -30
