#!/usr/bin/env bash
# Full build: port exteraless -> compile -> dex -> bundle the plugin.
# Usage: scripts/build.sh <exteraless-checkout> <inugram-classpath.jar>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$ROOT/.cache/tools"
"$ROOT/scripts/port.sh" "$@"

OUT="$ROOT/.cache/dex"
rm -rf "$OUT" && mkdir -p "$OUT"
# compat + generated classes are program input; the material color utilities are bundled too
java -cp "$T/r8.jar" com.android.tools.r8.D8 \
  --release --min-api 26 \
  --lib "$T/android.jar" \
  --classpath "$2" \
  --output "$OUT" \
  $(find "$ROOT/.cache/classes" -name '*.class') \
  "$T/material-color-utilities.jar"

SIZE=$(stat -c %s "$OUT/classes.dex")
if [ "$SIZE" -gt 8000000 ]; then
  echo "classes.dex is $SIZE bytes, over the 8 MB loadDex limit" >&2
  exit 1
fi
mkdir -p "$ROOT/src/assets"
cp "$OUT/classes.dex" "$ROOT/src/assets/md3player.dex"
echo "dex: $SIZE bytes"

cd "$ROOT"
pnpm exec inu build
