package de.ronny.poloretro

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** Liest ROMs aus ZIP-Dateien (die Cores bekommen immer eine ausgepackte Datei). */
object ZipRom {
    data class Found(val entryName: String, val system: RetroSystem)

    /** Erster Eintrag im ZIP, der wie ein ROM aussieht. Null bei leerem, defektem oder fremdem ZIP. */
    fun find(zip: File): Found? {
        return try {
            ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val system = RomNames.systemFor(e.name)
                    if (system != null) return Found(e.name, system)
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Packt das erste ROM aus dem ZIP nach [outDir] aus. Nur der reine Dateiname wird verwendet
     * (kein Pfad aus dem ZIP), damit ein präpariertes ZIP nichts außerhalb von [outDir] schreiben kann.
     */
    fun extract(zip: File, outDir: File): Pair<File, RetroSystem>? {
        val found = find(zip) ?: return null
        outDir.mkdirs()
        val target = File(outDir, File(found.entryName.replace('\\', '/')).name)
        return try {
            ZipFile(zip).use { zf ->
                val entry = zf.getEntry(found.entryName) ?: return null
                zf.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
            target to found.system
        } catch (_: Exception) {
            target.delete()
            null
        }
    }
}
