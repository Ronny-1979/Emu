package de.ronny.poloretro

import java.io.File

/** Erkennung und Beschriftung von ROM-Dateinamen. Keine Android-Klassen, damit es testbar bleibt. */
object RomNames {
    fun extension(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot < 0 || dot == base.length - 1) "" else base.substring(dot + 1).lowercase()
    }

    fun isZip(name: String): Boolean = extension(name) == "zip"

    /** System einer Datei anhand der Endung. ZIP-Dateien liefern hier null (Inhalt entscheidet). */
    fun systemFor(name: String): RetroSystem? = RetroSystem.fromExtension(extension(name))

    /** Ist die Datei ein ROM oder ein ZIP, das ein ROM enthalten könnte? */
    fun isCandidate(name: String): Boolean = isZip(name) || systemFor(name) != null

    /** Anzeigename: ohne Endung, ohne Klammer-Zusätze wie (USA) oder [!], Unterstriche als Leerzeichen. */
    fun title(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        var t = if (dot > 0) base.substring(0, dot) else base
        t = t.replace('_', ' ')
        t = t.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.isEmpty()) base else t
    }

    /** Dateisicherer Schlüssel für Spielstände dieses Spiels. */
    fun saveKey(file: File): String {
        val base = file.name.substringBeforeLast('.', file.name)
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        return if (safe.isEmpty()) "game" else safe.take(80)
    }
}
