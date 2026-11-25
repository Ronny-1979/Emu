package de.ronny.poloretro

import java.io.File

/** Erkennung und Beschriftung von ROM-Dateinamen. Keine Android-Klassen, damit es testbar bleibt. */
object RomNames {
    private val HASH_TAIL = Regex("(\\.[0-9A-Fa-f]{8,64})+$")

    /**
     * Entfernt angehängte Prüfsummen wie "Spiel (USA).MD.B362B705.339A8C9A96FC...". Nur wenn danach noch eine
     * Endung übrig bleibt, sonst bleibt der Name unverändert.
     */
    fun clean(name: String): String {
        val m = HASH_TAIL.find(name) ?: return name
        val stripped = name.substring(0, m.range.first)
        val base = stripped.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot > 0 && dot < base.length - 1) stripped else name
    }

    fun extension(rawName: String): String {
        val name = clean(rawName)
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
    fun title(rawFileName: String): String {
        val fileName = clean(rawFileName)
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
        val cleaned = clean(file.name)
        val base = cleaned.substringBeforeLast('.', cleaned)
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        return if (safe.isEmpty()) "game" else safe.take(80)
    }
}
