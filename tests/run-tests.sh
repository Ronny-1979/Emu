#!/bin/bash
# Logik-Tests (ohne Android-Gerät): ROM-Namen, ZIP, Tastenbelegung, Trigger, Sicherung.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../app/src/main/java/de/ronny/poloretro"
L=/opt/gradle/lib
CP="$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/kotlin-compiler-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar"
ANDROID=/home/claude/android-platforms/android-33/android.jar
OUT=$(mktemp -d)
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$ANDROID:$L/kotlin-stdlib-2.0.21.jar" -d "$OUT" \
  "$HERE/src/Tests.kt" "$HERE/stubs/BuildConfig.kt" \
  "$SRC/RomNames.kt" "$SRC/RetroSystem.kt" "$SRC/ZipRom.kt" "$SRC/ControllerMapping.kt" "$SRC/SaveBackup.kt" "$SRC/UsbImport.kt" "$SRC/DiagLog.kt" "$SRC/CheatStore.kt" "$SRC/CoverNames.kt" "$SRC/PadInput.kt" \
  2>&1 | grep -v "^warning: \|^Picked up" || true
java -cp "$OUT:$L/kotlin-stdlib-2.0.21.jar:$ANDROID" de.ronny.poloretro.TestsKt 2>&1 | grep -v "^Picked up"
