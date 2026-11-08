#!/usr/bin/env bash
# Converts an inugram APK into the classpath jar the porter and D8 compile against.
# Usage: scripts/classpath.sh <inugram.apk> <out.jar>
# Calls dex2jar directly: its d2j-dex2jar.sh hardcodes -Xmx2048m, which inugram outgrew in v13.2.
# Method bodies are kept (no --no-code): the porter loads R classes through a class loader.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D="$ROOT/.cache/tools/dex2jar"
[ -d "$D/lib" ] || { echo "dex2jar missing, run scripts/fetch-tools.sh" >&2; exit 1; }
java -Xms512m "-Xmx${DEX2JAR_HEAP:-6g}" -cp "$D/lib/*" com.googlecode.dex2jar.tools.Dex2jarCmd \
  -f -o "${2:?out jar}" "${1:?inugram apk}"
