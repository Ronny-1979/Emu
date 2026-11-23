# PoloRetro

Eigenständige Android-App für SNES, Sega Mega Drive, Nintendo 64, NES, Game Boy (Color/Advance), Master System und Game Gear, gebaut für ein Android-Autoradio
(Snapdragon 665, Android 13, arm64, 1280x720 quer, ohne Root). Paket: `de.ronny.poloretro`.
Hat nichts mit dem PoloLauncher zu tun.

**Nur im Stand spielen.**

## Technik
- [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) (GPL-3.0) als eigene Kopie im Ordner `libretrodroid/`, ergänzt um Run-Ahead und automatische Bildverzögerung (`runahead.h`, `framedelay.h`).
- Fertige arm64-Kerne aus [LemuroidCores](https://github.com/Swordfish90/LemuroidCores) in `app/src/main/jniLibs/arm64-v8a/`:
  snes9x, genesis_plus_gx (Mega Drive, Master System, Game Gear), mupen64plus_next_gles3, fceumm (NES), gambatte (Game Boy/Color), mgba (GBA).
- Kotlin, keine weiteren Bibliotheken, Oberfläche komplett im Code.

## Bauen
- Windows: `BUILD_APK_WINDOWS.bat` (JDK 17 und Android SDK nötig, dazu in Android Studio unter SDK Manager → SDK Tools „NDK (Side by side)“ und „CMake 3.22.1“; der Emulator-Unterbau wird jetzt mit gebaut, das dauert beim ersten Mal länger).
- GitHub: Workflow „Build APK“ (`.github/workflows/build-apk.yml`). Für Updates über eine bestehende Installation das Secret
  `DEBUG_KEYSTORE_BASE64` setzen (derselbe Debug-Schlüssel jedes Mal).
- Version bei jeder Änderung in `app/build.gradle` erhöhen (`versionCode`, `versionName`).

## ROMs
Ordner `PoloRetro` oder `Roms` auf dem USB-Stick (Unterordner erlaubt, z. B. `Roms/SNES/…`; die Konsole erkennt die App an der Dateiendung, bei ZIP am Inhalt; `.bin` gilt immer als Mega Drive), darin die ROM-Dateien (`.sfc .smc .md .gen .smd .bin .z64 .n64 .v64 .nes .gb .gbc .gba .sms .gg` oder als `.zip`).
Im Hauptmenü „USB-Import“:
- **Auf Radio kopieren**: Dateien landen im App-Speicher, der Stick wird danach nicht mehr gebraucht.
- **Vom Stick spielen**: der Ordner wird gemerkt. Die Liste bleibt auch ohne Stick sichtbar, Spielen geht nur mit eingestecktem Stick.

Der Stick braucht auf Android 11+ die Berechtigung „Zugriff auf alle Dateien“ (die App führt dorthin). Ohne sie bleibt nur
der Ordnerwähler (Kopieren) oder der App-Ordner `Android/data/de.ronny.poloretro/files/roms`.

## Spielstände
Immer im Radio, je Konsole und Spiel getrennt, unabhängig vom Ort der ROM: SRAM, 3 Speicherplätze und automatischer
Fortsetzungsstand (beim Verlassen). Einstellungen → „Auf USB-Stick sichern“ schreibt `PoloRetro/PoloRetro_Spielstaende.zip`.

## Steuerung
- Gespielt wird mit dem Bluetooth-Controller. Es gibt keine Touch-Spieltasten, nur einen kleinen „Menü“-Knopf oben rechts in der Ecke.
- Menü im Spiel: Zurück-Taste, oder Select+Start gedrückt halten, oder Mode/Home-Taste.
- Menü und Steuerungs-Seite lassen sich mit Steuerkreuz, A (anklicken) und B (zurück) bedienen.
- „Steuerung“ im Hauptmenü: je Konsole Funktion wählen, dann Taste am Controller drücken. N64: linker Stick = Analogstick, rechter Stick = C-Tasten.

## Titelbilder
Automatisch: Ist das Radio online, lädt die App fehlende Bilder von thumbnails.libretro.com (je Start höchstens 40 Spiele; Einstellungen → „Titelbilder jetzt laden“ holt alle). Das klappt nur, wenn der Dateiname dem üblichen Spielnamen entspricht (z. B. `Super Mario World (USA).sfc`).

Von Hand: Bilddatei (png/jpg) mit demselben Namen wie die ROM-Datei, ohne Endung: neben der ROM, im Unterordner `covers` daneben,
oder im App-Ordner `Android/data/de.ronny.poloretro/files/covers`. Beim Kopieren aufs Radio werden Bilder nicht mitkopiert,
dafür den App-Ordner benutzen.

## Cheats
Im Spielmenü „Cheats“: Name und Code eintragen (Game Genie / Action Replay, je nach Konsole), mit Haken ein- und ausschalten.
Gespeichert je Spiel. Ob ein bestimmter Code im Kern funktioniert, hängt vom Kern ab.

## Bekannte Grenzen
- Kein „Bildformat/Strecken“: LibretroDroid 0.14 bietet das nicht.
- Keine PlayStation (Disc-Dateien, BIOS und Leistung ungeklärt), kein Schnellvorlauf, kein Rückspulen (bewusst weggelassen).
- Zwei Spieler: Anschluss ergibt sich aus der Controller-Nummer von Android; nicht getestet.
- N64-Geschwindigkeit hängt vom Spiel ab; Die Auflösung (640x480 bis 1280x960) ist in den Einstellungen wählbar; 640x480 ist der sichere Start.

## Lizenz
Die Kerne und LibretroDroid stehen unter GPL. Diese App muss daher ebenfalls unter einer GPL-kompatiblen Lizenz weitergegeben werden.
ROMs sind nicht enthalten.
