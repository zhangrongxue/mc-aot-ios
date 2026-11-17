#!/bin/bash
# Build an unsigned development IPA for the Horizon iOS shell or engine app.
#
# Inputs:
#   HORIZON_DIR       checkout of horizon-rust/horizon with horizon-ios added
#   CARGO_TARGET_DIR  cargo target directory (default: $HORIZON_DIR/target)
#   APP_NAME          app/executable name (default: HorizonIOS)
#   MAIN_SOURCE       Objective-C entry source (default: $ROOT/ios/main.m)
#   BUNDLE_ID         CFBundleIdentifier (default: dev.horizon.ios.dev)
#   IPA_NAME          output IPA name (default: ${APP_NAME}-dev-unsigned.ipa)
#   BUILD_DIR         app build directory (default: $ROOT/build)
#   DIST_DIR          IPA output directory (default: $ROOT/dist)
#   BUILD_NUMBER      CFBundleVersion (default: UTC timestamp)
set -euxo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HORIZON_DIR="${HORIZON_DIR:-/tmp/horizon}"
TARGET_DIR="${CARGO_TARGET_DIR:-$HORIZON_DIR/target}"
BUILD_NUMBER="${BUILD_NUMBER:-$(date -u +%Y%m%d%H%M)}"
PROFILE="${PROFILE:-release}"
APP_NAME="${APP_NAME:-HorizonIOS}"
MAIN_SOURCE="${MAIN_SOURCE:-$ROOT/ios/main.m}"
BUNDLE_ID="${BUNDLE_ID:-dev.horizon.ios.dev}"
IPA_NAME="${IPA_NAME:-${APP_NAME}-dev-unsigned.ipa}"
SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
OUT="${BUILD_DIR:-$ROOT/build}"
APP="$OUT/$APP_NAME.app"
DIST="${DIST_DIR:-$ROOT/dist}"

rm -rf "$OUT" "$DIST"
mkdir -p "$APP" "$DIST"

STATIC_LIB="$TARGET_DIR/aarch64-apple-ios/$PROFILE/libhorizon_ios.a"
test -f "$STATIC_LIB"

xcrun --sdk iphoneos clang \
    -arch arm64 \
    -mios-version-min=15.0 \
    -isysroot "$SDK" \
    -fobjc-arc \
    -O2 \
    -Wl,-stack_size,0xA000000 \
    -framework UIKit \
    -framework Foundation \
    -framework CoreFoundation \
    -framework CoreGraphics \
    -framework Metal \
    -framework QuartzCore \
    -framework Security \
    -framework IOKit \
    -framework AudioToolbox \
    -framework CoreAudio \
    -framework AVFoundation \
    -framework GameController \
    "$MAIN_SOURCE" \
    "$STATIC_LIB" \
    -o "$APP/$APP_NAME"

cp "$ROOT/ios/Info.plist" "$APP/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUMBER" "$APP/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier $BUNDLE_ID" "$APP/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleExecutable $APP_NAME" "$APP/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleName $APP_NAME" "$APP/Info.plist"

# The engine app looks for a prepared resource tree in its bundle. It stays
# empty in the development package: Mojang assets are never bundled.
mkdir -p "$APP/horizon-client/26.3"

# Ad-hoc signature only: the IPA stays re-signable by Sideloadly/AltStore.
codesign --force --sign - --timestamp=none "$APP/$APP_NAME"
codesign --force --sign - --timestamp=none "$APP"

mkdir -p "$OUT/Payload"
cp -R "$APP" "$OUT/Payload/"
(cd "$OUT" && zip -qry "$DIST/$IPA_NAME" Payload)

echo "--- artifact ---"
ls -lh "$DIST/$IPA_NAME"
shasum -a 256 "$DIST/$IPA_NAME"
echo "--- app ---"
file "$APP/$APP_NAME"
plutil -p "$APP/Info.plist" | head -40
unzip -l "$DIST/$IPA_NAME" | head -30
