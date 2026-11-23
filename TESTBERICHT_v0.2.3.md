# Testbericht PoloRetro 0.2.3

## Hier im Entwicklungsrechner geprüft
- `tests/run-tests.sh`: 108 Prüfungen der reinen Logik, 0 Fehler (ROM-Namen und Endungen inkl. neuer Konsolen, Cheat-Liste, Titelbild-Suche, ZIP-Entpacken inkl. Schutz vor
  Pfadausbruch, Tastenbelegung und Änderung, Trigger-Schwellen, Totzone, Dateinamen-Bereinigung, Sicherung und
  Wiederherstellung inkl. Schutz vor Pfadausbruch).
- `tests/compile-check.sh`: alle Kotlin-Quellen übersetzen fehlerfrei gegen die Android-33-API. LibretroDroid, androidx und
  Lifecycle sind dabei nur Nachbauten (Stubs), abgeglichen mit dem echten LibretroDroid-Quellcode (Stand master), nicht mit 0.14.0.
- Alle XML-Dateien sind wohlgeformt. Die drei Kerne sind echte aarch64-ELF-Dateien.

## NICHT geprüft (konnte hier nicht laufen)
- **Kein Gradle-Build**: Maven Central, Google Maven und JitPack sind hier gesperrt. Ob die Abhängigkeiten
  (LibretroDroid 0.14.0 über JitPack) und das Packaging aufgehen, zeigt erst Android Studio oder GitHub Actions.
- Alles auf dem Gerät: Start der Kerne, Bild, Ton, Bluetooth-Controller, Menü per Controller,
  USB-Stick-Zugriff (Berechtigung „alle Dateien“), Spielstände und Fortsetzen, N64-Geschwindigkeit.
- Neu in 0.2.0/0.2.3 und nur auf dem Gerät prüfbar: Download der Titelbilder (hier kein Zugang zum Server; Ordnernamen und Adressschema nur aus der libretro-Dokumentation, nicht ausprobiert), Kerne für NES/Game Boy/GBA/Master System/Game Gear, Vorschaubilder (PixelCopy), Cheats im Kern, Rütteln, N64-Steckmodul, Titelbilder, Spielernummer bei zwei Controllern.
- Annahme, die erst am Radio beweisbar ist: Speichern/Laden bei pausierter Anzeige (Aufruf direkt vom Hauptthread).
