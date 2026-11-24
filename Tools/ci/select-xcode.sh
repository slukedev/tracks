#!/bin/bash
# Points xcode-select at an installed Xcode: the version given, or the newest one.
#
#   Tools/ci/select-xcode.sh [27.2]
set -euo pipefail

want="${1:-}"
if [ -n "$want" ]; then
  app="/Applications/Xcode_$want.app"
else
  app="$(ls -d /Applications/Xcode_*.app 2>/dev/null | grep -E 'Xcode_[0-9]+(\.[0-9]+)*\.app$' | sort -V | tail -1)"
fi
if [ ! -d "${app:-/nonexistent}" ]; then
  echo "::error::Xcode ${want:-(any)} not found. Installed:"
  ls -d /Applications/Xcode*.app
  exit 1
fi
sudo xcode-select -s "$app/Contents/Developer"
xcodebuild -version
sdk="$(xcrun --sdk iphonesimulator --show-sdk-version)"
echo "iOS Simulator SDK $sdk"
if [ "${sdk%%.*}" -lt 27 ]; then
  echo "::error::$app has the iOS $sdk SDK; the apps need iOS 27. Use a runner image with Xcode 27."
  exit 1
fi
