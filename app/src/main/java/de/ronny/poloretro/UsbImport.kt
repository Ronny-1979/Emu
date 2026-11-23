package de.ronny.poloretro

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Eine ROM-Datei auf dem USB-Stick (direkt lesbar oder über die Dateiauswahl). */
interface RomSource {
    val name: String
    val size: Long
    fun open(): InputStream
}

class FileSource(private val file: File) : RomSource {
    override val name: String get() = file.name
    override val size: Long get() = file.length()
    override fun open(): InputStream = FileInputStream(file)
}

class DocSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
) : RomSource {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Datei nicht lesbar: $name")
}

object UsbImport {
    /** Ordnername auf dem Stick, den die App zuerst sucht. */
    const val STICK_FOLDER = "PoloRetro"

    private val SKIP_DIRS = setOf("android", "lost.dir", "system volume information", "\$recycle.bin")

    /** Hat die App Zugriff auf beliebige Dateien (nötig für Wechseldatenträger ab Android 11)? */
    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** Alle Wechseldatenträger, die sich direkt lesen lassen. */
    fun candidateRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, File>()
        fun add(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            if (dir.listFiles() == null) return
            val key = try { dir.canonicalPath } catch (_: IOException) { dir.absolutePath }
            roots.putIfAbsent(key, dir)
        }
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null && Build.VERSION.SDK_INT >= 30) {
                for (v in sm.storageVolumes) if (!v.isPrimary) add(v.directory)
            }
        } catch (_: RuntimeException) {
            // weiter mit der einfachen Suche
        }
        for (base in listOf("/storage", "/mnt/media_rw")) {
            base.let { File(it).listFiles() }?.forEach { child ->
                val n = child.name.lowercase()
                if (n != "emulated" && n != "self") add(child)
            }
        }
        return roots.values.toList()
    }

    /** Ordnernamen auf dem Stick, die als ROM-Sammlung gelten (in dieser Reihenfolge, Groß/Klein egal). */
    val ROM_FOLDER_NAMES = listOf(STICK_FOLDER, "Roms", "ROM", "Spiele", "Games")

    /** Ordner auf dem Stick, der die ROMs enthält: "PoloRetro" oder "Roms" (u. ä.), falls vorhanden, sonst der Stick selbst. */
    fun libraryFolder(root: File): File {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return root
        for (name in ROM_FOLDER_NAMES) {
            dirs.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        }
        return root
    }

    /** ROMs auf einem Stick: bevorzugt im ROM-Ordner samt Unterordnern (z. B. Roms/SNES/...), sonst lose im Stammverzeichnis. */
    fun scanFolder(root: File): List<RomSource> {
        val out = ArrayList<RomSource>()
        val folder = libraryFolder(root)
        if (folder != root) walk(folder, 0, 5, out) else walk(root, 0, 2, out)
        return out
    }

    private fun walk(dir: File, depth: Int, maxDepth: Int, out: MutableList<RomSource>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val n = child.name.lowercase()
                if (depth < maxDepth && !n.startsWith(".") && n !in SKIP_DIRS) walk(child, depth + 1, maxDepth, out)
            } else if (RomNames.isCandidate(child.name)) {
                // Fremde ZIP-Dateien vom Stick (Fotos, Backups ...) nicht mitnehmen.
                if (RomNames.isZip(child.name) && ZipRom.find(child) == null) continue
                out.add(FileSource(child))
            }
        }
    }

    /** ROMs aus einem Ordner, den man mit der System-Dateiauswahl gewählt hat. */
    fun scanTree(context: Context, tree: Uri): List<RomSource> {
        val resolver = context.contentResolver
        val out = ArrayList<RomSource>()
        fun walkTree(documentId: String, depth: Int) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            resolver.query(childrenUri, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 4 && !name.startsWith(".")) walkTree(id, depth + 1)
                    } else if (RomNames.isCandidate(name)) {
                        out.add(DocSource(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size))
                    }
                }
            }
        }
        walkTree(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    class Summary(val copied: Int, val skipped: Int, val failed: Int, val cancelled: Boolean)

    fun totalBytes(sources: List<RomSource>): Long = sources.sumOf { maxOf(0L, it.size) }

    /** Dateiname ohne Pfadanteile, damit nichts außerhalb des Zielordners landen kann. */
    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return if (base.isEmpty() || base == "." || base == "..") "rom.bin" else base
    }

    /**
     * Kopiert die Dateien nach [destDir]. Schon vorhandene Dateien gleicher Größe werden übersprungen,
     * so kann man den Import gefahrlos wiederholen. Es wird erst in eine .part-Datei geschrieben.
     */
    fun copy(
        sources: List<RomSource>,
        destDir: File,
        progress: (done: Int, total: Int, name: String, bytes: Long) -> Unit,
        cancelled: () -> Boolean,
    ): Summary {
        destDir.mkdirs()
        var copied = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        for ((index, source) in sources.withIndex()) {
            if (cancelled()) return Summary(copied, skipped, failed, true)
            progress(index, sources.size, source.name, bytes)
            val target = File(destDir, safeName(source.name))
            if (target.exists() && source.size >= 0 && target.length() == source.size) {
                skipped++
                continue
            }
            val part = File(destDir, target.name + ".part")
            try {
                source.open().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelled()) throw IOException("abgebrochen")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            bytes += n
                        }
                        output.fd.sync()
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen")
                copied++
            } catch (e: IOException) {
                part.delete()
                if (cancelled()) return Summary(copied, skipped, failed, true)
                failed++
                DiagLog.add("Import fehlgeschlagen: ${source.name}: ${e.message}")
            }
        }
        progress(sources.size, sources.size, "", bytes)
        return Summary(copied, skipped, failed, false)
    }

    /** Schreibt Text (z. B. das Protokoll) in den Ordner PoloRetro auf dem ersten lesbaren Stick. */
    fun exportText(context: Context, fileName: String, text: String): File? {
        for (root in candidateRoots(context)) {
            try {
                val dir = File(root, STICK_FOLDER).apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeText(text)
                return file
            } catch (_: Exception) {
                // nächster Stick
            }
        }
        return null
    }
}
