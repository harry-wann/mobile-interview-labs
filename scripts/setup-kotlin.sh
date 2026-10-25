#!/usr/bin/env bash
# 下載 Kotlin 編譯器與 kotlinx-coroutines 到專案內的 .tools/（不動全域環境）
set -euo pipefail

KOTLIN_VERSION="${KOTLIN_VERSION:-2.4.21}"
COROUTINES_VERSION="${COROUTINES_VERSION:-1.11.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
mkdir -p "$TOOLS/lib"

if [ ! -x "$TOOLS/kotlinc/bin/kotlinc" ]; then
  echo "下載 Kotlin $KOTLIN_VERSION ..."
  tmp="$(mktemp -d)"
  curl -fL --retry 3 -o "$tmp/kotlin.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"
  unzip -q "$tmp/kotlin.zip" -d "$TOOLS"
  rm -rf "$tmp"
fi

jar="$TOOLS/lib/kotlinx-coroutines-core-jvm-${COROUTINES_VERSION}.jar"
if [ ! -f "$jar" ]; then
  echo "下載 kotlinx-coroutines $COROUTINES_VERSION ..."
  curl -fL --retry 3 -o "$jar" \
    "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/${COROUTINES_VERSION}/kotlinx-coroutines-core-jvm-${COROUTINES_VERSION}.jar"
fi

"$TOOLS/kotlinc/bin/kotlinc" -version 2>&1 | head -1
echo "完成：$TOOLS"
