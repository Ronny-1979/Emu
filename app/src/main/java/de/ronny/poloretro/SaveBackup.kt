package de.ronny.poloretro

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Sichert Spielstände in eine ZIP-Datei (z. B. auf den USB-Stick) und spielt sie wieder ein. */
object SaveBackup {
    const val FILE_NAME = "PoloRetro_Spielstaende.zip"

    /** Packt die Ordner [names] unterhalb von [baseDir] nach [out]. Gibt die Zahl der Dateien zurück. */
    fun export(baseDir: File, names: List<String>, out: File): Int {
        out.parentFile?.mkdirs()
        val part = File(out.parentFile, out.name + ".part")
        var count = 0
        try {
            ZipOutputStream(FileOutputStream(part).buffered()).use { zip ->
                for (name in names) {
                    val root = File(baseDir, name)
                    if (!root.isDirectory) continue
                    root.walkTopDown().filter { it.isFile }.forEach { file ->
                        val rel = file.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry(rel))
                        FileInputStream(file).use { it.copyTo(zip, 64 * 1024) }
                        zip.closeEntry()
                        count++
                    }
                }
            }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) throw IOException("Umbenennen fehlgeschlagen")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return count
    }

    /**
     * Spielt eine Sicherung ein. Es werden nur Dateien unterhalb der erlaubten Ordner [allowed]
     * geschrieben; Pfade, die aus [baseDir] herausführen würden, werden ignoriert.
     */
    fun restore(zip: File, baseDir: File, allowed: Set<String>): Int {
        val basePath = baseDir.canonicalPath + File.separator
        var count = 0
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() }) continue
                if (name.substringBefore('/') !in allowed) continue
                val target = File(baseDir, name)
                if (!target.canonicalPath.startsWith(basePath)) continue
                target.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
                }
                count++
            }
        }
        return count
    }
}
