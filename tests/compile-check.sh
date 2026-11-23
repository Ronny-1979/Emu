#!/bin/bash
# Kompiliert alle App-Quellen gegen android.jar + Stubs (ohne Gradle). Prüft nur die Übersetzbarkeit.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
L=/opt/gradle/lib
CP="$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/kotlin-compiler-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar"
ANDROID=/home/claude/android-platforms/android-33/android.jar
OUT=$(mktemp -d)
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$ANDROID:$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar" \
  -d "$OUT" "$HERE/stubs" $(find "$HERE/../app/src/main/java" -name '*.kt') 2>&1 | grep -v "^warning: \|JAVA_TOOL_OPTIONS" || true
echo "fertig: $OUT"
