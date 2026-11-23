package de.ronny.poloretro

import java.io.File

/** Cheat-Liste eines Spiels als einfache Textdatei: eine Zeile je Cheat "1|Name|CODE" (1 = an, 0 = aus). */
data class Cheat(val name: String, val code: String, val enabled: Boolean)

object CheatStore {
    fun load(file: File): List<Cheat> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<Cheat>()
        try {
            for (line in file.readLines()) {
                val parts = line.split('|', limit = 3)
                if (parts.size != 3) continue
                val code = parts[2].trim()
                if (code.isEmpty()) continue
                out.add(Cheat(parts[1].trim().ifEmpty { code }, code, parts[0] == "1"))
            }
        } catch (_: Exception) {
            return out
        }
        return out
    }

    fun save(file: File, cheats: List<Cheat>) {
        file.parentFile?.mkdirs()
        val text = cheats.joinToString("\n") { c ->
            (if (c.enabled) "1" else "0") + "|" + clean(c.name) + "|" + clean(c.code)
        }
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(text)
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) throw java.io.IOException("Umbenennen fehlgeschlagen")
    }

    /** Trennzeichen und Zeilenumbrüche würden das Dateiformat zerstören. */
    private fun clean(s: String): String = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim()
}

/** Findet ein Titelbild zu einem Spiel: gleicher Name wie die ROM-Datei, als png/jpg/jpeg. */
object Covers {
    private val EXT = listOf("png", "jpg", "jpeg")

    fun find(romFile: File, saveKey: String, extraDirs: List<File>): File? {
        val base = romFile.name.substringBeforeLast('.', romFile.name)
        val dirs = ArrayList<File>()
        romFile.parentFile?.let { dirs.add(it); dirs.add(File(it, "covers")) }
        dirs.addAll(extraDirs)
        for (dir in dirs) {
            for (name in listOf(base, saveKey)) {
                for (e in EXT) {
                    val f = File(dir, "$name.$e")
                    if (f.isFile && f.length() > 0) return f
                }
            }
        }
        return null
    }
}
