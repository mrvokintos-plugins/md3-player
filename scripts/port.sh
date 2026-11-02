#!/usr/bin/env bash
# Runs the porter: exteraless player sources -> generated/java + compiled classes.
# Usage: scripts/port.sh <exteraless-checkout> <inugram-classpath.jar>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM="${1:?exteraless checkout}"
CPJAR="${2:?inugram classpath jar}"
T="$ROOT/.cache/tools"
"$ROOT/scripts/fetch-tools.sh" >/dev/null
mkdir -p "$ROOT/.cache/porter"
javac -nowarn -encoding UTF-8 -d "$ROOT/.cache/porter" -cp "$T/javaparser.jar" "$ROOT/porter/src/md3porter/Porter.java"
SHA="${PORT_SHA:-$(git -C "$UPSTREAM" log -1 --format=%H -- TMessagesProj/src/main/java/app/exteraless/player 2>/dev/null || echo unknown)}"
SHA="${SHA:0:9}"
java -cp "$ROOT/.cache/porter:$T/javaparser.jar" md3porter.Porter \
  --upstream "$UPSTREAM" \
  --classpath "$T/android.jar:$CPJAR:$T/material-color-utilities.jar" \
  --compat "$ROOT/compat" \
  --rules "$ROOT/porter/rules.txt" \
  --out "$ROOT/generated" \
  --classes "$ROOT/.cache/classes" \
  --sha "$SHA"
