# Testbericht v0.3.0 (Run-Ahead, Bildverzögerung, eigenes LibretroDroid-Modul)

## Hier geprüft
- Logik von Run-Ahead und automatischer Bildverzögerung mit einem Schein-Kern am PC (`tests/native-test.sh`): 65 Prüfungen, 0 Fehler. Geprüft u. a.: angezeigtes Bild ist genau ein Bild weiter; der echte Spielstand stimmt nach dem Zurückspielen mit einem normalen Lauf überein; gleich viele Bilder und Tonblöcke; sauberer Rückfall, wenn der Kern keine Zustände sichern kann (auch mitten im Spiel); Wartezeit steigt langsam, fällt bei Ausreißern sofort, kein verspätetes Bild bei Zittern der Rechenzeit. Ein absichtlich eingebauter Fehler wurde von den Tests erkannt.
- Alle 31 C++-Dateien des Moduls auf Syntax geprüft (g++ -fsyntax-only mit Khronos-Grafikheadern und kleinen Android-Stubs). 30 laufen durch; `fpssync.cpp` meldet einen Fehler, der nur mit dem PC-Compiler auftritt (Typunterschied `long`/`long long` der Standardbibliothek); diese Datei ist bis auf eine Hilfsfunktion in der Header-Datei unverändert.
- App-Quellen kompilieren gegen android.jar und Stubs; die 108 Logik-Tests laufen fehlerfrei.

## Hier NICHT geprüft
- Der native Build mit echtem NDK und CMake (hier nicht herunterladbar), also auch Linken und Laden der Bibliothek.
- Die Kotlin-Dateien des Moduls (GLRetroView.kt usw.) wurden nicht mit echten androidx-Bibliotheken übersetzt; die App wird gegen Stubs geprüft.
- Alles auf dem Radio: ob Run-Ahead oder Bildverzögerung die Verzögerung spürbar senken, ob die Rechenleistung reicht, ob der Ton dabei sauber bleibt.
- Der Quellcode von LibretroDroid ist der aktuelle Entwicklungsstand, nicht die Version 0.14.0, die bisher benutzt wurde.

## Nicht gebaut
- Hard GPU Sync (RetroArch): in LibretroDroid gibt es keine Stelle, an der man die Warteschlange des Grafiktreibers sinnvoll begrenzen kann.
