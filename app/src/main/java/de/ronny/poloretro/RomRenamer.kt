package de.ronny.poloretro

import java.io.File

/**
 * Erkennt Spiele aller Konsolen an der Prüfsumme (CRC32) und gibt ihnen den richtigen Namen.
 * Nur bei einem exakten Treffer in der Datenbank, es wird nichts geraten. Nur Dateien im App-Speicher (Kopien auf dem Radio),
 * nie Dateien auf dem USB-Stick. Spielstände, Titelbilder, Favoriten und Spielzeit ziehen mit um.
 */
class RomRenamer(private val paths: AppPaths, private val prefs: Prefs) {
    private val appRoots: List<File> get() = listOfNotNull(paths.romDir, paths.externalRomDir).map { it.canonicalFile }

    private fun inAppFolder(file: File): Boolean {
        val f = try { file.canonicalFile } catch (_: Exception) { return false }
        return appRoots.any { f.path.startsWith(it.path + File.separator) }
    }

    /** Liefert die Spieleliste mit den neuen Dateinamen; [renamed] zählt die umbenannten Spiele. */
    fun renameAll(games: List<Game>, dbFor: (RetroSystem) -> Map<Long, String>?, cancelled: () -> Boolean): Pair<List<Game>, Int> {
        var renamed = 0
        val out = ArrayList<Game>(games.size)
        val dbs = HashMap<RetroSystem, Map<Long, String>?>()
        val checked = loadChecked()
        var changed = false
        for (g in games) {
            if (cancelled()) { out.add(g); continue }
            val key = checkKey(g.file)
            if (key != null && key in checked) { out.add(g); continue }
            val db = dbs.getOrPut(g.system) { dbFor(g.system) }
            val updated = try { renameOne(g, db) } catch (_: Exception) { null }
            // Nur merken, wenn die Datenbank da war: ohne Datenbank wird beim nächsten Mal noch einmal geprüft.
            if (db != null && db.isNotEmpty() && g.available && !g.external) {
                val k = checkKey(updated?.file ?: g.file)
                if (k != null) { checked.add(k); changed = true }
            }
            if (updated != null) { out.add(updated); renamed++ } else out.add(g)
        }
        if (changed) saveChecked(checked)
        return out to renamed
    }

    // ---- Merkliste: schon geprüfte Dateien (Name|Größe|Datum), damit nicht bei jedem Lauf alles neu gerechnet wird ----

    private val checkedFile: File get() = File(paths.baseDir, "rename_checked.txt")

    private fun checkKey(f: File): String? = if (f.isFile) "${f.name}|${f.length()}|${f.lastModified()}" else null

    private fun loadChecked(): MutableSet<String> = try {
        if (checkedFile.isFile) checkedFile.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
    } catch (_: Exception) {
        mutableSetOf()
    }

    private fun saveChecked(set: Set<String>) {
        try { checkedFile.writeText(set.joinToString("\n")) } catch (_: Exception) { /* nicht schlimm */ }
    }

    private fun renameOne(g: Game, db: Map<Long, String>?): Game? {
        if (db == null || db.isEmpty()) return null
        if (!g.available || g.external || RomNames.isZip(g.file.name)) return null
        val file = g.file
        if (!file.isFile || file.length() <= 0 || !inAppFolder(file)) return null
        val hit = RomId.identify(file, db, g.system) ?: return null
        val name = hit.name
        // Glatte Sega-ROMs bekommen die übliche Endung; alle anderen behalten ihre Endung, weil sie zum Inhalt gehört.
        val ext = if (hit.canonicalExt) g.system.extensions.first() else RomNames.extension(file.name).ifEmpty { g.system.extensions.first() }
        val desired = CoverNames.fileName(name) + "." + ext
        if (desired == file.name) return null
        val target = File(file.parentFile, desired)
        if (target.exists()) {
            DiagLog.add("Umbenennen: \"${file.name}\" ist \"$name\", aber \"$desired\" gibt es schon")
            return null
        }
        if (!file.renameTo(target)) return null
        migrate(g, file.name, target)
        DiagLog.add("Umbenannt: \"${file.name}\" -> \"${target.name}\"")
        return g.copy(file = target, title = RomNames.title(target.name))
    }

    /** Alles, was am Dateinamen hängt, zieht auf den neuen Namen um. */
    private fun migrate(g: Game, oldName: String, newFile: File) {
        val sys = g.system
        val oldKey = RomNames.saveKey(File(oldName))
        val newKey = RomNames.saveKey(newFile)
        if (oldKey != newKey) {
            val base = paths.baseDir
            fun move(dir: File, from: String, to: String) {
                val a = File(dir, from)
                val b = File(dir, to)
                if (a.isFile && !b.exists()) a.renameTo(b)
            }
            move(File(base, "sram/${sys.id}"), "$oldKey.srm", "$newKey.srm")
            val states = File(base, "states/${sys.id}")
            move(states, "$oldKey.auto.state", "$newKey.auto.state")
            for (slot in 1..AppPaths.SLOTS) {
                move(states, "$oldKey.slot$slot.state", "$newKey.slot$slot.state")
                move(states, "$oldKey.slot$slot.jpg", "$newKey.slot$slot.jpg")
            }
            move(File(base, "cheats/${sys.id}"), "$oldKey.txt", "$newKey.txt")
            File(base, "saves/${sys.id}").listFiles()?.filter { it.name.startsWith("$oldKey.") }?.forEach {
                move(it.parentFile!!, it.name, newKey + it.name.removePrefix(oldKey))
            }
            for (e in listOf("png", "jpg", "jpeg")) move(paths.coverDirFor(sys), "$oldKey.$e", "$newKey.$e")
        }
        prefs.renameGameKeys(sys, oldName, oldKey, newFile.name, newKey)
    }
}
