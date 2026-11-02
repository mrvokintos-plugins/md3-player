#!/usr/bin/env bash
# Downloads build tools into .cache/tools (idempotent). Used locally and in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$ROOT/.cache/tools"
mkdir -p "$T"
cd "$T"

PLATFORM_ZIP=platform-36_r02.zip
R8_VERSION=9.5.28
DEX2JAR_VERSION=2.4.38
JAVAPARSER_VERSION=3.28.2

fetch() { [ -s "$2" ] || { echo "fetch $1"; curl -fsSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"; }; }

if [ ! -s android.jar ]; then
  fetch "https://dl.google.com/android/repository/$PLATFORM_ZIP" platform.zip
  unzip -p platform.zip '*/android.jar' > android.jar
  rm -f platform.zip
fi
fetch "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar" r8.jar
fetch "https://repo1.maven.org/maven2/com/github/javaparser/javaparser-core/$JAVAPARSER_VERSION/javaparser-core-$JAVAPARSER_VERSION.jar" javaparser.jar
if [ ! -d dex2jar ]; then
  fetch "https://github.com/ThexXTURBOXx/dex2jar/releases/download/$DEX2JAR_VERSION/dex-tools-$DEX2JAR_VERSION.zip" dex2jar.zip
  unzip -q dex2jar.zip -d dex2jar.tmp && mv dex2jar.tmp/* dex2jar && rm -rf dex2jar.tmp dex2jar.zip
  chmod +x dex2jar/*.sh
fi


# material-color-utilities (Apache-2.0), only the color.utilities package is bundled into the dex
MATERIAL_VERSION=1.14.0
if [ ! -s material-color-utilities.jar ]; then
  fetch "https://dl.google.com/dl/android/maven2/com/google/android/material/material/$MATERIAL_VERSION/material-$MATERIAL_VERSION.aar" material.aar
  rm -rf mat.tmp && mkdir mat.tmp && unzip -q -o material.aar classes.jar -d mat.tmp
  (cd mat.tmp && mkdir x && cd x && unzip -q ../classes.jar 'com/google/android/material/color/utilities/*' && zip -q -r ../../material-color-utilities.jar .)
  rm -rf mat.tmp material.aar
fi
echo "material color utilities ready"
echo "tools ready in $T"
