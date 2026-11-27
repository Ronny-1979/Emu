package de.ronny.poloretro

import android.content.Context
import java.io.File

/**
 * Ein Spiel in der Bibliothek.
 * [available] ist false, wenn die Datei auf einem Stick liegt, der gerade nicht eingesteckt ist.
 * Spielstände hängen nur an [system] und [saveKey], nicht am Ort der ROM-Datei.
 */
data class Game(
    val file: File,
    val system: RetroSystem,
    val title: String,
    val available: Boolean = true,
    /** Liegt die Datei in einem gemerkten Bibliotheksordner (z. B. auf dem USB-Stick)? */
    val external: Boolean = false,
) {
    /** Schlüssel für Favoriten und "zuletzt gespielt". */
    val key: String get() = file.name
    val saveKey: String get() = RomNames.saveKey(file)
}

/** Wo die App Dateien ablegt. Alles liegt im privaten App-Speicher. */
class AppPaths(val context: Context) {
    val romDir: File get() = File(context.filesDir, "roms").apply { mkdirs() }

    /** App-Ordner auf dem Gerätespeicher, in den man per Dateimanager/MTP ROMs legen kann. */
    val externalRomDir: File? get() = context.getExternalFilesDir("roms")?.apply { mkdirs() }

    val systemDir: File get() = File(context.filesDir, "system").apply { mkdirs() }
    val tempRomDir: File get() = File(context.cacheDir, "rom").apply { mkdirs() }

    /** Alles, was zu den Spielständen gehört (für Sicherung und Wiederherstellung). */
    val saveRoots: List<File>
        get() = listOf("saves", "sram", "states", "cheats").map { File(context.filesDir, it) }

    val baseDir: File get() = context.filesDir

    fun savesDir(system: RetroSystem): File = File(context.filesDir, "saves/${system.id}").apply { mkdirs() }

    fun sramFile(game: Game): File =
        File(context.filesDir, "sram/${game.system.id}/${game.saveKey}.srm").also { it.parentFile?.mkdirs() }

    fun autoStateFile(game: Game): File =
        File(context.filesDir, "states/${game.system.id}/${game.saveKey}.auto.state").also { it.parentFile?.mkdirs() }

    fun slotStateFile(game: Game, slot: Int): File =
        File(context.filesDir, "states/${game.system.id}/${game.saveKey}.slot$slot.state").also { it.parentFile?.mkdirs() }

    /** Vorschaubild zu einem Speicherplatz. */
    fun slotThumbFile(game: Game, slot: Int): File =
        File(context.filesDir, "states/${game.system.id}/${game.saveKey}.slot$slot.jpg").also { it.parentFile?.mkdirs() }

    /** Cheat-Liste dieses Spiels (bleibt beim Löschen der Spielstände erhalten). */
    fun cheatFile(game: Game): File =
        File(context.filesDir, "cheats/${game.system.id}/${game.saveKey}.txt").also { it.parentFile?.mkdirs() }

    /** Ordner für Titelbilder auf dem Gerät: Datei heißt wie das Spiel (ohne Endung), als png/jpg. */
    val coverDir: File get() = File(context.filesDir, "covers").apply { mkdirs() }

    /** Heruntergeladene Bilder liegen je Konsole getrennt (gleiche Spielnamen auf verschiedenen Konsolen). */
    fun coverDirFor(system: RetroSystem): File = File(coverDir, system.id).apply { mkdirs() }

    val externalCoverDir: File? get() = context.getExternalFilesDir("covers")?.apply { mkdirs() }

    fun deleteSaves(game: Game) {
        sramFile(game).delete()
        autoStateFile(game).delete()
        for (slot in 1..SLOTS) {
            slotStateFile(game, slot).delete()
            slotThumbFile(game, slot).delete()
        }
        // Dateien, die der Core selbst im Speicherordner anlegt (z. B. N64-Speicherkarte).
        val dir = File(context.filesDir, "saves/${game.system.id}")
        dir.listFiles()?.filter { it.name.startsWith(game.saveKey + ".") }?.forEach { it.delete() }
    }

    companion object {
        const val SLOTS = 3
    }
}

/** Sucht ROMs in den App-Ordnern und in gemerkten Bibliotheksordnern (z. B. USB-Stick). */
class RomLibrary(private val context: Context) {
    private val paths = AppPaths(context)
    private val prefs = Prefs(context)
    private val indexFile = File(context.filesDir, "library_index.txt")

    fun appFolders(): List<File> = listOfNotNull(paths.romDir, paths.externalRomDir)

    fun scan(): List<Game> {
        val result = ArrayList<Game>()
        val seen = HashSet<String>()

        for (dir in appFolders()) {
            val found = ArrayList<Game>()
            walk(dir, 0, found, external = false)
            for (g in found) if (seen.add(g.file.name.lowercase())) result.add(g)
        }

        // Gemerkte Ordner: Ist der Stick da, wird gelesen und der Index erneuert, sonst der Index benutzt.
        val cached = readIndex()
        val newIndex = ArrayList<IndexEntry>()
        for (folderPath in prefs.libraryFolders()) {
            val folder = File(folderPath)
            val readable = folder.isDirectory && folder.listFiles() != null
            if (readable) {
                val found = ArrayList<Game>()
                walk(folder, 0, found, external = true)
                for (g in found) {
                    newIndex.add(IndexEntry(g.file.absolutePath, g.system, g.title))
                    if (seen.add(g.file.name.lowercase())) result.add(g)
                }
            } else {
                val prefix = folder.absolutePath.trimEnd('/') + "/"
                for (e in cached) {
                    if (!e.path.startsWith(prefix)) continue
                    newIndex.add(e)
                    val file = File(e.path)
                    if (seen.add(file.name.lowercase())) {
                        result.add(Game(file, e.system, e.title, available = false, external = true))
                    }
                }
            }
        }
        writeIndex(cached, newIndex)

        result.sortBy { it.title.lowercase() }
        return result
    }

    private fun walk(dir: File, depth: Int, out: MutableList<Game>, external: Boolean) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val n = child.name.lowercase()
                if (depth < 5 && !n.startsWith(".") && n !in SKIP_DIRS) walk(child, depth + 1, out, external)
                continue
            }
            val system = systemOf(child) ?: continue
            out.add(Game(child, system, RomNames.title(child.name), available = true, external = external))
        }
    }

    private fun systemOf(file: File): RetroSystem? {
        if (RomNames.isZip(file.name)) return zipSystem(file)
        val byExt = RomNames.systemFor(file.name) ?: return null
        if (byExt != RetroSystem.MEGADRIVE && byExt != RetroSystem.MASTERSYSTEM && byExt != RetroSystem.GAMEGEAR) return byExt
        val key = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
        synchronized(sniffCache) { sniffCache[key]?.let { return it } }
        val header = try {
            file.inputStream().use { input ->
                val buf = ByteArray(RomSniffer.HEADER_BYTES)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        } catch (_: Exception) {
            return byExt
        }
        val system = RomSniffer.refine(byExt, header)
        synchronized(sniffCache) { sniffCache[key] = system }
        return system
    }

    private fun zipSystem(file: File): RetroSystem? {
        val cacheKey = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
        synchronized(zipCache) {
            if (zipCache.containsKey(cacheKey)) return zipCache[cacheKey]
        }
        val system = ZipRom.find(file)?.system
        synchronized(zipCache) { zipCache[cacheKey] = system }
        return system
    }

    // ---- Index der Spiele auf Wechseldatenträgern (damit die Liste auch ohne Stick sichtbar bleibt) ----

    data class IndexEntry(val path: String, val system: RetroSystem, val title: String)

    private fun readIndex(): List<IndexEntry> {
        return try {
            if (!indexFile.exists()) emptyList() else indexFile.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val system = RetroSystem.fromId(parts[1]) ?: return@mapNotNull null
                IndexEntry(parts[0], system, parts[2])
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(old: List<IndexEntry>, new: List<IndexEntry>) {
        if (old == new) return
        try {
            indexFile.writeText(new.joinToString("\n") { "${it.path}\t${it.system.id}\t${it.title}" })
        } catch (_: Exception) {
            // Index ist nur Komfort.
        }
    }

    companion object {
        private val zipCache = HashMap<String, RetroSystem?>()
        private val sniffCache = HashMap<String, RetroSystem>()

        /** Ordner, in denen nie ROMs liegen (System-, Papierkorb- und Sicherungsordner). */
        private val SKIP_DIRS = setOf("android", "lost.dir", "system volume information", "\$recycle.bin", "backup", "saves")
    }
}
