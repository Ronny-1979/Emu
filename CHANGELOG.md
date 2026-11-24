# Änderungen

## 0.3.10
- Titelbilder in der Liste werden vollständig angezeigt (nicht mehr rechts abgeschnitten): Bild wird eingepasst statt gefüllt, Feld etwas breiter (96 x 72 dp).

## 0.3.9
- Titelbilder jetzt über GitHub: Die Bilder liegen als Dateien in den Repositories der Organisation libretro-thumbnails (z. B. Nintendo_-_Super_Nintendo_Entertainment_System). Die App holt sie zuerst von dort (raw.githubusercontent.com), weil der libretro-Server auf dem Radio für alles 404 liefert. Die Namensliste für die Ähnlichkeitssuche kommt von der GitHub-Schnittstelle. Der libretro-Server bleibt als Ersatz.
- Bei 404 steht im Protokoll zusätzlich die Server-Kennung und der Anfang der Antwort.

## 0.3.8
- Titelbilder nach dem Vorbild von RetroArch: zusätzlich die Spielszene (Named_Snaps) als letzter Ausweg, und bei einer 404-Antwort von https wird einmal http probiert (RetroArch nutzt laut Dokumentation http://thumbnails.libretro.com). Das Ergebnis steht im Protokoll.

## 0.3.7
- Titelbilder: Der Server liefert die Ordnerliste nicht mehr als Webseite (404). Die Namensliste wird jetzt zuerst über die Datei ".index" geholt, wie es RetroArch macht, und nur zur Not über die Ordnerseite.
- Bilder werden zusätzlich mit sparsam verschlüsselten Adressen versucht (Klammern, Komma und Apostroph unverschlüsselt).
- Im Protokoll stehen jetzt die ersten 404-Adressen und der Anfang der Antwort bei einer unbrauchbaren Namensliste, damit sich die Ursache erkennen lässt.

## 0.3.6
- Automatische Bildverzögerung aus den Einstellungen entfernt. Run-Ahead allein reicht (auf dem Radio bei SNES ohne spürbare Verzögerung). Der Unterbau dafür bleibt unverändert im Hintergrund, wird aber nicht mehr eingeschaltet.

## 0.3.5
- Titelbilder: Bei einem Netzfehler steht im Protokoll jetzt die genaue Fehlerart (z. B. Zertifikat, Namensauflösung, Zeitüberschreitung), damit sich die Ursache finden lässt, wenn keine Bilder laden.

## 0.3.4
- Neu gebaut mit eigenem Signaturschlüssel (Secret DEBUG_KEYSTORE_BASE64), damit sich die APK über die bestehende Installation legen lässt. Sonst keine Änderung.

## 0.3.3
- Protokoll lässt sich teilen: Einstellungen → "Protokoll teilen / per Mail senden" (auch im Protokoll-Fenster als "Teilen / Mail"). Öffnet die Android-Teilen-Auswahl, z. B. für eine Mail-App. Der Text steckt direkt in der Nachricht.

## 0.3.2
- Startseite mit dem Controller bedienbar: Mit dem Kreuz kommt man jetzt auch von den Reitern hinunter in die Spieleliste. Die Zeilen haben einen sichtbaren Rahmen, wenn sie ausgewählt sind. A startet das Spiel, X öffnet das Spielmenü (wie langes Drücken mit dem Finger). Vorher war die Liste mit dem Controller nicht erreichbar (nicht sichtbar markiert).

## 0.3.1
- Controller, die sich (je nach Betriebsart) als Tastatur statt als Gamepad melden, funktionieren jetzt auch im Spiel und auf der Steuerung-Seite: Kreuz und die Tasten A/B/X/Y, Start, Select und Schultertasten werden als Controller-Tasten erkannt. Vorher reagierte so ein Controller nur im Menü.
- Das Protokoll nennt je Eingabegerät einmal, wie es sich meldet (Name, Quelle, Spielernummer, erste Taste, ob als Controller erkannt).

## 0.3.0
- Neu: Der Emulator-Unterbau (LibretroDroid) liegt jetzt als eigenes Modul im Projekt und wird mitgebaut (statt von JitPack geladen). Dafür braucht der Build NDK und CMake 3.22.1.
- Neu: Run-Ahead (Einstellungen → Eingabeverzögerung). Jedes Bild wird zweimal gerechnet, das angezeigte Bild ist ein Bild weiter, Eingaben wirken ein Bild früher (etwa 16 ms). Nur für 2D-Konsolen, nicht N64. Kostet Rechenleistung. Kann der Kern keine Zustände sichern, läuft das Spiel normal weiter.
- Neu: Automatische Bildverzögerung. Der Kern wird erst kurz vor dem nächsten Bildtakt gerechnet, die Wartezeit passt sich der Rechenzeit an.
- Beides ist standardmäßig aus und wirkt ab dem nächsten Spielstart. Das Protokoll nennt beim Spielstart, ob Run-Ahead wirklich aktiv ist.
- Der Quellcode von LibretroDroid stammt aus dem aktuellen Stand der Entwicklung (nicht aus der Version 0.14.0).

## 0.2.7
- Eingaben: Steuerkreuz- und Stick-Bewegungen des Controllers werden bei Android 12+ ohne Wartezeit auf den nächsten Bildtakt zugestellt. Ob das auf dem Radio etwas bringt, ist ungetestet.

## 0.2.6
- Neu: „Bilder pro Sekunde anzeigen (Fehlersuche)“ in den Einstellungen. Blendet oben links im Spiel die gezeichneten Bilder pro Sekunde und die Bildwiederholrate des Radio-Bildschirms ein. Das Protokoll bekommt alle 10 Sekunden einen Wert.
- Das Protokoll nennt beim Spielstart immer die Bildwiederholrate des Bildschirms.

## 0.2.5
- Ton: Der Audiofokus wird jetzt standardmäßig nicht mehr angefordert (so macht es auch Lemuroid/LibretroDroid; manche Radios schalten bei einer Fokus-Anfrage den Ton weg). Die Einstellung startet einmalig wieder auf „aus“. Wenn eingeschaltet, wird er als normaler Medien-Ton angefordert statt als Spiel-Signalton.

## 0.2.4
- Menü-Knopf oben rechts deutlich kleiner; der Berührbereich bleibt dabei großzügig.
- N64-Auflösung jetzt 640x480 (Standard), 960x720 oder 1280x960. 320x240 entfällt. Die Auswahl startet einmalig wieder bei 640x480.

## 0.2.3
- Titelbilder: Findet der Server den genauen Dateinamen nicht, lädt die App die Namensliste der Konsole und sucht den ähnlichsten Titel (z. B. „Aladdin (Europe)“ findet „Disney's Aladdin (Europe)“). Die Liste wird einen Monat zwischengespeichert.
- Das Protokoll zeigt jetzt, welcher Servername gewählt wurde, oder dass es keinen Treffer gab.

## 0.2.2
- Ton: „Schneller Audiomodus“ ist jetzt standardmäßig aus (manche Radios geben damit keinen Ton aus); in den Einstellungen einschaltbar.
- Das Protokoll enthält jetzt beim Spielstart den Tonstatus (Lautstärke, Ausgänge, Audiofokus).

## 0.2.1
- Titelbilder werden automatisch aus dem Internet geladen (thumbnails.libretro.com), wenn das Radio online ist; in den Einstellungen abschaltbar oder von Hand startbar. Passt nur bei üblichen Dateinamen wie „Super Mario World (USA).sfc“.
- ROM-Ordner auf dem Stick: neben „PoloRetro“ wird auch „Roms“ (auch „ROM“, „Spiele“, „Games“) mit Unterordnern erkannt. Die Konsole ergibt sich aus der Dateiendung bzw. dem Inhalt der ZIP-Datei.

## 0.2.0
- Neue Konsolen: NES, Game Boy, Game Boy Color, Game Boy Advance, Master System, Game Gear (Kerne fceumm, gambatte, mgba; Master System/Game Gear laufen im Genesis-Plus-GX-Kern).
- Hauptmenü: Auswahl „Konsole“ statt eigener Tabs je Konsole.
- Titelbilder in der Liste (Bild mit dem Namen der ROM neben der ROM, im Unterordner „covers“, oder im App-Ordner „covers“).
- Vorschaubilder bei den Speicherplätzen im Spielmenü.
- Cheats je Spiel (Code eintragen, an/aus, löschen).
- Bildfilter einstellbar je Spiel.
- Controller rütteln (abschaltbar); N64: Steckmodul Speicherkarte/Rüttelmodul/keins wählbar.
- Meldung „Controller verbunden (Spieler N)“; Anschluss 1 bis 4 je nach Controller-Nummer.
- Sicherung der Spielstände enthält nun auch die Cheats.

## 0.1.2
- Der „Menü“-Knopf sitzt jetzt klein in der oberen rechten Ecke statt oben in der Mitte.

## 0.1.1
- Touch-Spieltasten entfernt (am Radio unpraktisch). Es bleibt nur ein kleiner „Menü“-Knopf (ab 0.1.2 oben rechts), damit das Pausenmenü auch ohne Controller erreichbar ist. Gespielt wird mit dem Bluetooth-Controller.
- Einstellungen und Spielmenü ohne Touch-Tasten-Optionen.

## 0.1.0
Erste Version.
- Emulation von SNES (snes9x), Mega Drive (Genesis Plus GX) und N64 (Mupen64Plus-Next GLES3) über LibretroDroid.
- Touch-Tasten (Querformat, Multitouch) und Bluetooth-Controller.
- Tastenbelegung je Konsole, direkt mit dem Controller einstellbar, mit Live-Test.
- ROM-Import vom USB-Stick (kopieren oder direkt vom Stick spielen), ZIP-ROMs werden automatisch entpackt.
- Merkt sich ROM-Ordner (auch bei gezogenem Stick sichtbar), Spielstände liegen immer im Radio.
- 3 Speicherplätze je Spiel, SRAM, automatisches Sichern beim Verlassen und Fortsetzen beim nächsten Start.
- Sicherung und Wiederherstellung der Spielstände auf/vom USB-Stick.
- Bildfilter (weich, scharf, CRT, LCD), N64-Auflösung, Protokoll.
