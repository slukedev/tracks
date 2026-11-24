#!/bin/bash
# Captures iPhone screenshots of a Debug build on sample data, for CI.
#
#   Tools/ci/ios-screenshots.sh <Tracks.app> <out-dir> [shots]
#
# shots is a list of `name: launch arguments` separated by ";" or newlines. Without it, the
# key screens below are captured. Each shot is taken in light and dark, as
# <name>-light.png and <name>-dark.png. Every launch adds -TracksDemoData YES, so the app uses
# its in-memory sample data and never touches iCloud or Last.fm.
set -euo pipefail

app="${1:?usage: ios-screenshots.sh <Tracks.app> <out-dir> [shots]}"
out="${2:?usage: ios-screenshots.sh <Tracks.app> <out-dir> [shots]}"
shots="${3:-}"
device="${IPHONE_SIMULATOR:-}"
wait_seconds="${SHOT_WAIT:-6}"

default_shots='
play: -TracksTab play
now-playing: -TracksDemoPlaying station -TracksNowPlaying YES
summary: -TracksTab summary -statsRange month
charts: -TracksTab charts -chartKind songs
your-music: -TracksTab play -musicSource yourMusic
settings: -TracksSettings YES
'
[ -n "${shots//[[:space:];]/}" ] || shots="$default_shots"

[ -d "$app" ] || { echo "✗ no app at $app" >&2; exit 1; }
bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")"
mkdir -p "$out"

# The newest iOS runtime's iPhone, preferring a Pro Max, unless IPHONE_SIMULATOR names one.
udid="$(xcrun simctl list devices available -j | python3 -c '
import json, sys, re
want = sys.argv[1]
runtimes = json.load(sys.stdin)["devices"]
def version(key):
    m = re.search(r"iOS-(\d+)-(\d+)", key)
    return (int(m.group(1)), int(m.group(2))) if m else None
ios = sorted((k for k in runtimes if version(k)), key=version, reverse=True)
for key in ios:
    phones = [d for d in runtimes[key] if d["name"].startswith("iPhone")]
    if want:
        phones = [d for d in phones if d["name"] == want]
    if not phones:
        continue
    phones.sort(key=lambda d: ("Pro Max" not in d["name"], "Pro" not in d["name"], d["name"]))
    print(phones[0]["udid"], phones[0]["name"], key.rsplit(".", 1)[-1], sep="\t")
    break
' "$device")"
if [ -z "$udid" ]; then
  echo "✗ no iPhone simulator${device:+ called $device}. Available:" >&2
  xcrun simctl list devices available >&2
  exit 1
fi
IFS=$'\t' read -r udid name runtime <<<"$udid"
echo "Simulator: $name ($runtime) $udid"

xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b >/dev/null
xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active \
  --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl install "$udid" "$app"

# A first launch warms the app's caches, so the first shot isn't of a half-drawn screen.
xcrun simctl launch "$udid" "$bundle_id" -TracksDemoData YES >/dev/null
sleep 10
xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true

failed=0
while IFS= read -r line; do
  line="${line#"${line%%[![:space:]]*}"}"
  [ -n "$line" ] || continue
  shot="${line%%:*}"
  args="${line#*:}"
  [ "$args" != "$line" ] || args=""
  shot="${shot//[^A-Za-z0-9._-]/-}"
  # Arguments are split like a shell would, so a quoted value can hold spaces.
  eval "set -- $args"
  for look in light dark; do
    xcrun simctl ui "$udid" appearance "$look"
    xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true
    if ! xcrun simctl launch "$udid" "$bundle_id" -TracksDemoData YES "$@" >/dev/null; then
      echo "✗ $shot-$look: launch failed"
      failed=1
      continue
    fi
    sleep "$wait_seconds"
    if xcrun simctl io "$udid" screenshot "$out/$shot-$look.png" >/dev/null 2>&1; then
      echo "  $shot-$look.png"
    else
      echo "✗ $shot-$look: screenshot failed"
      failed=1
    fi
  done
done < <(printf '%s\n' "$shots" | tr ';' '\n')

xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true
xcrun simctl ui "$udid" appearance light
count="$(find "$out" -name '*.png' | wc -l | tr -d ' ')"
echo "Saved $count screenshots to $out"
[ "$count" -gt 0 ] || { echo "✗ no screenshots captured" >&2; exit 1; }
exit "$failed"
