package de.ronny.poloretro

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Lädt fehlende Titelbilder von thumbnails.libretro.com. Das Ergebnis liegt im App-Ordner "covers". */
class CoverDownloader(private val paths: AppPaths) {
    enum class Fetch { OK, NOT_FOUND, ERROR }

    class Result(val found: Int, val missing: Int, val errors: Int, val alreadyThere: Int, val cancelled: Boolean)

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
        games: List<Game>,
        retryMissing: Boolean,
        maxGames: Int,
        progress: (done: Int, total: Int, title: String) -> Unit,
        cancelled: () -> Boolean,
    ): Result {
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
            for (url in CoverNames.candidates(game.system, game.file.name)) {
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
                    when (fetch(CoverNames.url(game.system, CoverNames.BOXART, match), File(paths.coverDirFor(game.system), game.saveKey + ".png"))) {
                        Fetch.OK -> { ok = true; DiagLog.add("Titelbild: \"${game.file.name}\" -> \"$match\"") }
                        Fetch.ERROR -> anyError = true
                        Fetch.NOT_FOUND -> {}
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
        return Result(found, notFound, errors, alreadyThere, wasCancelled)
    }

    private var netErrorsLogged = 0

    /** Schreibt die genaue Fehlerart ins Protokoll (die ersten paar je Lauf), damit man die Ursache sieht. */
    private fun logNetError(address: String, e: Exception) {
        if (netErrorsLogged++ >= 4) return
        val cause = e.cause?.let { " / Ursache: ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        DiagLog.add("Titelbild-Netzfehler: ${e.javaClass.simpleName}: ${e.message}$cause (${address.substringBefore('?').take(80)})")
    }

    private class Resp(val code: Int, val bytes: ByteArray?)

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
                Resp(code, null)
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

    private fun fetch(address: String, target: File): Fetch {
        val r = get(address, 3_000_000)
        if (r.code == -1) { DiagLog.add("Titelbild: Netzfehler bei $address"); return Fetch.ERROR }
        if (r.code == 404) return Fetch.NOT_FOUND
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
        val r = get(dirUrl, 12_000_000)
        val html = r.bytes?.toString(Charsets.UTF_8)
        if (html == null) {
            DiagLog.add("Titelbild: Namensliste ${system.shortLabel} nicht ladbar (HTTP ${r.code})")
            return emptyList()
        }
        val names = Regex("href=\"([^\"]+?)\\.png\"", RegexOption.IGNORE_CASE).findAll(html)
            .map { java.net.URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8") }
            .filter { !it.contains('/') }
            .toList()
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
