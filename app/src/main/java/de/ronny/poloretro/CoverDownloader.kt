package de.ronny.poloretro

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Lädt fehlende Titelbilder von thumbnails.libretro.com. Das Ergebnis liegt im App-Ordner "covers". */
class CoverDownloader(private val paths: AppPaths) {
    enum class Fetch { OK, NOT_FOUND, ERROR }

    class Result(val found: Int, val missing: Int, val errors: Int, val alreadyThere: Int, val cancelled: Boolean, val renamed: Int = 0)

    private val missingFile: File get() = File(paths.coverDir, ".missing")

    private fun loadMissing(): MutableSet<String> =
        try {
            if (missingFile.isFile) missingFile.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
        } catch (_: Exception) {
            mutableSetOf()
        }

    private fun saveMissing(set: Set<String>) {
        try {
            missingFile.writeText(set.joinToString("\n"))
        } catch (_: Exception) {
            // nicht schlimm: dann wird beim nächsten Mal noch einmal gefragt
        }
    }

    fun hasCover(game: Game): Boolean =
        Covers.find(game.file, game.saveKey, listOfNotNull(paths.coverDirFor(game.system), paths.coverDir, paths.externalCoverDir)) != null

    /**
     * Versucht für alle Spiele ohne Bild eines zu laden.
     * [retryMissing]: auch Spiele erneut probieren, für die der Server beim letzten Mal nichts hatte.
     * [maxGames]: höchstens so viele Spiele je Lauf (damit der Start nicht ewig dauert); der Rest folgt beim nächsten Lauf.
     */
    fun run(
        gamesIn: List<Game>,
        retryMissing: Boolean,
        maxGames: Int,
        progress: (done: Int, total: Int, title: String) -> Unit,
        cancelled: () -> Boolean,
    ): Result {
        // Zuerst richtig benennen (Sega-Spiele per Prüfsumme), danach passen die Namen zu den Titelbildern.
        var games = gamesIn
        var renamedCount = 0
        try {
            val (renamedList, n) = RomRenamer(paths, Prefs(paths.context)).renameAll(gamesIn, { romDb(it) }, cancelled)
            games = renamedList
            renamedCount = n
            if (n > 0) DiagLog.add("Spiele richtig benannt: $n")
        } catch (t: Throwable) {
            DiagLog.add("Umbenennen: ${t.javaClass.simpleName}: ${t.message}")
        }
        val missing = loadMissing()
        val todo = games.filter { it.available && !hasCover(it) && (retryMissing || "${it.system.id}/${it.saveKey}" !in missing) }
        val alreadyThere = games.count { it.available && hasCover(it) }
        val list = todo.take(maxGames)
        if (retryMissing) missing.removeAll(list.map { "${it.system.id}/${it.saveKey}" }.toSet())
        var found = 0
        var errors = 0
        var notFound = 0
        var consecutiveErrors = 0
        var wasCancelled = false
        val indexes = HashMap<RetroSystem, List<String>>()
        for ((i, game) in list.withIndex()) {
            if (cancelled()) { wasCancelled = true; break }
            progress(i, list.size, game.title)
            var ok = false
            var anyError = false
            // Sega-Spiele: Der Name aus der Datenbank (per Prüfsumme) ist genauer als der Dateiname.
            val exact = try { romDb(game.system)?.let { RomId.identify(game.file, it, game.system) } } catch (_: Exception) { null }
            val coverName = if (exact != null) exact.name + "." + game.system.extensions.first() else game.file.name
            if (exact != null && exact.name != game.file.name.substringBeforeLast('.')) DiagLog.add("Titelbild: \"${game.file.name}\" erkannt als \"${exact.name}\"" + if (exact.exact) " (Prüfsumme)" else " (Produktnummer)")
            for (url in CoverNames.candidates(game.system, coverName)) {
                if (cancelled()) break
                when (fetch(url, File(paths.coverDirFor(game.system), game.saveKey + ".png"))) {
                    Fetch.OK -> { ok = true }
                    Fetch.ERROR -> anyError = true
                    Fetch.NOT_FOUND -> {}
                }
                if (ok) break
                if (anyError) break
            }
            if (!ok && !anyError && !cancelled()) {
                // Genauer Name unbekannt: in der Namensliste des Servers den ähnlichsten suchen.
                val names = indexes.getOrPut(game.system) { serverNames(game.system) }
                val match = CoverNames.bestMatch(game.file.name, names)
                if (match != null) {
                    val target = File(paths.coverDirFor(game.system), game.saveKey + ".png")
                    // Die Liste stammt von GitHub, dort gibt es den Namen sicher; der libretro-Server ist nur die zweite Wahl.
                    var res = fetch(CoverNames.githubUrl(game.system, CoverNames.BOXART, match), target)
                    if (res != Fetch.OK) res = fetch(CoverNames.url(game.system, CoverNames.BOXART, match), target)
                    when (res) {
                        Fetch.OK -> { ok = true; DiagLog.add("Titelbild: \"${game.file.name}\" -> \"$match\"") }
                        Fetch.ERROR -> anyError = true
                        Fetch.NOT_FOUND -> DiagLog.add("Titelbild: Treffer \"$match\" für \"${game.file.name}\", Bild aber nicht ladbar")
                    }
                } else {
                    DiagLog.add("Titelbild: kein Treffer für \"${game.file.name}\" (${names.size} Einträge geprüft)")
                }
            }
            when {
                ok -> { found++; consecutiveErrors = 0 }
                anyError -> {
                    errors++
                    consecutiveErrors++
                    // Kein Netz: nicht weiter versuchen.
                    if (consecutiveErrors >= 3) break
                }
                else -> { notFound++; consecutiveErrors = 0; missing.add("${game.system.id}/${game.saveKey}") }
            }
        }
        saveMissing(missing)
        progress(list.size, list.size, "")
        DiagLog.add("Titelbilder: $found geladen, $notFound beim Server nicht vorhanden, $errors Netzfehler")
        return Result(found, notFound, errors, alreadyThere, wasCancelled, renamedCount)
    }

    private val rdbCache = HashMap<RetroSystem, RomDbData?>()

    /** Namen und Prüfsummen der Konsole aus der libretro-Datenbank (einmal geladen, 90 Tage gemerkt). Null ohne Netz und ohne Zwischenspeicher. */
    fun romDb(system: RetroSystem): RomDbData? {
        return rdbCache.getOrPut(system) { loadRomDb(system) }
    }

    private fun loadRomDb(system: RetroSystem): RomDbData? {
        val cache = File(paths.coverDirFor(system), ".rdb")
        val cached = try { if (cache.isFile) RomDb.parseFull(cache.readBytes()) else null } catch (_: Exception) { null }
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < 90L * 24 * 3600 * 1000
        if (fresh && cached != null && cached.crc.isNotEmpty()) return cached
        val url = "https://raw.githubusercontent.com/libretro/libretro-database/master/rdb/" +
            java.net.URLEncoder.encode(system.thumbFolder, "UTF-8").replace("+", "%20") + ".rdb"
        val r = get(url, 16_000_000)
        val bytes = r.bytes
        if (r.code == 200 && bytes != null) {
            val map = RomDb.parseFull(bytes)
            if (map.crc.isNotEmpty()) {
                try { cache.writeBytes(bytes) } catch (_: Exception) { /* egal */ }
                DiagLog.add("Datenbank ${system.shortLabel}: ${map.crc.size} Spiele")
                return map
            }
        }
        DiagLog.add("Datenbank ${system.shortLabel} nicht ladbar (HTTP ${r.code})")
        return cached?.takeIf { it.crc.isNotEmpty() }
    }

    private var netErrorsLogged = 0
    private var notFoundLogged = 0

    /** Schreibt die genaue Fehlerart ins Protokoll (die ersten paar je Lauf), damit man die Ursache sieht. */
    private fun logNetError(address: String, e: Exception) {
        if (netErrorsLogged++ >= 4) return
        val cause = e.cause?.let { " / Ursache: ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        DiagLog.add("Titelbild-Netzfehler: ${e.javaClass.simpleName}: ${e.message}$cause (${address.substringBefore('?').take(80)})")
    }

    private class Resp(val code: Int, val bytes: ByteArray?, val info: String = "")

    private fun get(address: String, maxBytes: Int): Resp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 15000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PoloRetro")
            }
            val code = conn.responseCode
            if (code == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size > maxBytes) Resp(code, null) else Resp(code, bytes)
            } else {
                // Fehlerantwort: Server-Kennung und Anfang des Textes merken (zur Fehlersuche im Protokoll).
                val body = try { conn.errorStream?.use { it.readBytes().take(100).toByteArray().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
                val who = listOfNotNull(conn.getHeaderField("Server"), conn.getHeaderField("Via")).joinToString("/")
                Resp(code, null, "Server=${who.ifEmpty { "?" }}, Text: ${body?.replace('\n', ' ')?.trim() ?: "-"}")
            }
        } catch (e: IOException) {
            logNetError(address, e)
            Resp(-1, null)
        } catch (e: RuntimeException) {
            logNetError(address, e)
            Resp(-1, null)
        } finally {
            conn?.disconnect()
        }
    }

    /** RetroArch fragt laut Dokumentation "http://thumbnails.libretro.com" ab. Antwortet https mit 404, wird einmal http probiert. */
    private var useHttp = false
    private var httpProbes = 0

    private fun fetch(address: String, target: File): Fetch {
        var addr = address
        if (useHttp && addr.startsWith("https://")) addr = "http://" + addr.removePrefix("https://")
        var r = get(addr, 3_000_000)
        if (r.code == 404 && addr.startsWith("https://") && !addr.contains("githubusercontent.com") && httpProbes < 2) {
            httpProbes++
            val alt = get("http://" + addr.removePrefix("https://"), 3_000_000)
            DiagLog.add("Titelbild: https lieferte 404, http-Probe: HTTP ${alt.code}")
            if (alt.code == 200) { useHttp = true; r = alt; addr = "http://" + addr.removePrefix("https://") }
        }
        return handle(addr, r, target)
    }

    private fun handle(address: String, r: Resp, target: File): Fetch {
        if (r.code == -1) { DiagLog.add("Titelbild: Netzfehler bei $address"); return Fetch.ERROR }
        if (r.code == 404) {
            if (notFoundLogged++ < 4) DiagLog.add("Titelbild: HTTP 404 bei $address (${r.info})")
            return Fetch.NOT_FOUND
        }
        if (r.code != 200) { DiagLog.add("Titelbild: HTTP ${r.code} bei $address"); return Fetch.ERROR }
        val bytes = r.bytes
        if (bytes == null || bytes.size < 100 || !isPng(bytes)) {
            DiagLog.add("Titelbild: Antwort ist kein PNG bei $address")
            return Fetch.NOT_FOUND
        }
        return try {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            part.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (part.renameTo(target)) Fetch.OK else Fetch.ERROR
        } catch (_: IOException) {
            Fetch.ERROR
        }
    }

    /** Namensliste des Servers (Ordner Named_Boxarts) für eine Konsole, einen Monat lang zwischengespeichert. */
    private fun serverNames(system: RetroSystem): List<String> {
        val cache = File(paths.coverDirFor(system), ".index.txt")
        try {
            if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < 30L * 24 * 3600 * 1000) {
                val lines = cache.readLines().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) return lines
            }
        } catch (_: Exception) {
            // neu laden
        }
        val dirUrl = "${CoverNames.SERVER}/" + java.net.URLEncoder.encode(system.thumbFolder, "UTF-8").replace("+", "%20") + "/${CoverNames.BOXART}/"
        // 1. GitHub-Schnittstelle: Dateiliste des Ordners Named_Boxarts im Repository der Konsole (ein Aufruf, einen Monat gemerkt).
        try {
            val api = get("${CoverNames.GITHUB_API}/${system.thumbRepo}/git/trees/master:${CoverNames.BOXART}", 12_000_000)
            val json = api.bytes?.toString(Charsets.UTF_8)
            if (json != null) {
                val arr = org.json.JSONObject(json).optJSONArray("tree")
                val list = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i).optString("path")
                    if (p.endsWith(".png", ignoreCase = true)) list.add(p.substringBeforeLast('.'))
                }
                DiagLog.add("Titelbild: Namensliste ${system.shortLabel} über GitHub: ${list.size} Einträge")
                if (list.isNotEmpty()) {
                    try { cache.writeText(list.joinToString("\n")) } catch (_: Exception) { /* egal */ }
                    return list
                }
            } else {
                DiagLog.add("Titelbild: GitHub-Namensliste ${system.shortLabel} nicht ladbar (HTTP ${api.code})")
            }
        } catch (e: Exception) {
            DiagLog.add("Titelbild: GitHub-Namensliste ${system.shortLabel}: ${e.javaClass.simpleName}: ${e.message}")
        }
        // 2. Der libretro-Server bietet je Ordner eine Datei ".index" mit allen Namen (so lädt auch RetroArch); die Ordnerseite
        // selbst liefert inzwischen 404. Beides wird probiert.
        var text: String? = null
        var lastCode = 0
        for (u in listOf(dirUrl + ".index", dirUrl)) {
            val r = get(u, 12_000_000)
            lastCode = r.code
            val t = r.bytes?.toString(Charsets.UTF_8)
            if (t != null && t.contains(".png", ignoreCase = true)) { text = t; break }
            DiagLog.add("Titelbild: Namensliste ${system.shortLabel} bei \"${u.substringAfterLast('/').ifEmpty { "Ordnerseite" }}\" nicht brauchbar (HTTP ${r.code}${if (t != null) ", ${t.length} Zeichen, Anfang: ${t.take(80).replace('\n', ' ')}" else ""})")
        }
        if (text == null) {
            DiagLog.add("Titelbild: Namensliste ${system.shortLabel} nicht ladbar (HTTP $lastCode)")
            return emptyList()
        }
        val html: String = text
        val fromLinks = Regex("href=\"([^\"]+?)\\.png\"", RegexOption.IGNORE_CASE).findAll(html)
            .map { java.net.URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8") }
            .filter { !it.contains('/') }
            .toList()
        // Einfache Textliste: ein Name je Zeile (mit oder ohne Endung), ggf. mit weiteren Spalten nach einem Tabulator.
        val names = fromLinks.ifEmpty {
            html.lineSequence().map { it.substringBefore('\t').trim() }
                .filter { it.endsWith(".png", ignoreCase = true) }
                .map { it.substringBeforeLast('.').substringAfterLast('/') }
                .toList()
        }
        DiagLog.add("Titelbild: Namensliste ${system.shortLabel}: ${names.size} Einträge")
        if (names.isNotEmpty()) {
            try { cache.writeText(names.joinToString("\n")) } catch (_: Exception) { /* egal */ }
        }
        return names
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    /** Vergisst, was der Server nicht kannte, damit alles neu versucht wird. */
    fun forgetMisses() {
        missingFile.delete()
    }
}
