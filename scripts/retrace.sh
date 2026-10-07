#!/bin/zsh
# Retraces an obfuscated PlayTranslate crash log with the archived R8 mapping
# of the build that produced it.
#
#   scripts/retrace.sh <logcat-or-stack-file>
#
# Every obfuscated frame in a release-build log carries the build's map id
# ("r8-map-id-<hex>"); app/build.gradle.kts archives each release build's
# mapping under releases/ as mapping-<version>-<versionCode>-<first 8 of the
# id>.txt, and this picks the one the log names. The retraced log goes to
# stdout; frames from the framework and lines that are not frames pass
# through unchanged.
set -e

log="$1"
if [ -z "$log" ] || [ ! -f "$log" ]; then
  echo "usage: $0 <logcat-or-stack-file>" >&2
  exit 2
fi
root="$(cd "$(dirname "$0")/.." && pwd)"

id=$(grep -o 'r8-map-id-[0-9a-f]*' "$log" | head -1 | sed 's/^r8-map-id-//')
if [ -z "$id" ]; then
  echo "$log has no r8-map-id: not a release build, or no obfuscated frames." >&2
  exit 1
fi

map=$(ls "$root"/releases/mapping-*-"${id:0:8}".txt 2>/dev/null | head -1)
if [ -z "$map" ]; then
  echo "No mapping for map id ${id:0:8} under $root/releases/." >&2
  echo "Any release build of that version writes it; see releases/README.md." >&2
  exit 1
fi

sdk=$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null)
retrace="${sdk:-$HOME/Library/Android/sdk}/cmdline-tools/latest/bin/retrace"
if [ ! -x "$retrace" ]; then
  echo "retrace not found at $retrace (Android SDK command-line tools)." >&2
  exit 1
fi

echo "mapping: $map" >&2
exec "$retrace" "$map" "$log"
