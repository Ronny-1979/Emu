package de.ronny.poloretro

import java.net.URLEncoder

/** Namensregeln von thumbnails.libretro.com (Bilder heißen wie das Spiel im No-Intro-Namen, als PNG). */
object CoverNames {
    const val SERVER = "https://thumbnails.libretro.com"
    const val BOXART = "Named_Boxarts"
    const val TITLE = "Named_Titles"

    private val FORBIDDEN = Regex("[&*/:`<>?\\\\|\"]")

    /** Diese Zeichen werden im Dateinamen der Bilder durch "_" ersetzt. */
    fun fileName(base: String): String = FORBIDDEN.replace(base, "_")

    /** Spielname ohne Endung und mögliche Schreibweisen davon, genaueste zuerst. */
    fun variants(romFileName: String): List<String> {
        val base = romFileName.substringBeforeLast('.', romFileName).trim()
        val noBrackets = base.replace(Regex("\\s*\\[[^\\]]*]"), "").trim()
        val firstParen = Regex("^[^(]*\\([^)]*\\)").find(noBrackets)?.value?.trim()
        return listOfNotNull(base, noBrackets, firstParen).filter { it.isNotEmpty() }.distinct()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Wie [enc], lässt aber Klammern, Komma, Apostroph und Ausrufezeichen unverschlüsselt (so schreibt es auch RetroArch). */
    private fun encLight(s: String): String = enc(s)
        .replace("%28", "(").replace("%29", ")").replace("%27", "'").replace("%2C", ",").replace("%21", "!")

    fun url(system: RetroSystem, kind: String, name: String): String =
        "$SERVER/${enc(system.thumbFolder)}/$kind/${enc(fileName(name))}.png"

    /** Gleiche Adresse mit sparsamer Verschlüsselung; manche Server mögen %28/%29 nicht. */
    fun urlLight(system: RetroSystem, kind: String, name: String): String =
        "$SERVER/${enc(system.thumbFolder)}/$kind/${encLight(fileName(name))}.png"

    /** Alle Adressen, die für ein Spiel probiert werden: Titelbild-Schachtel in allen Schreibweisen, dann Titelbildschirm. */
    fun candidates(system: RetroSystem, romFileName: String): List<String> {
        val names = variants(romFileName)
        val out = ArrayList<String>()
        for (n in names) {
            out.add(url(system, BOXART, n))
            out.add(urlLight(system, BOXART, n))
        }
        names.firstOrNull()?.let {
            out.add(url(system, TITLE, it))
            out.add(urlLight(system, TITLE, it))
        }
        return out.distinct()
    }

    // ------------------------------------------------------------ Unscharfe Suche in der Serverliste

    private val NOISE_WORDS = setOf("the")
    private val BAD_TAGS = listOf("beta", "proto", "demo", "sample", "unl", "pirate", "hack", "kiosk", "promo")

    private fun titlePart(name: String): String {
        val cut = name.indexOfAny(charArrayOf('(', '['))
        return if (cut > 0) name.substring(0, cut) else name
    }

    private fun words(title: String): List<String> =
        title.lowercase().replace("'", "").replace("\u2019", "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in NOISE_WORDS }

    private fun tags(name: String): List<String> =
        Regex("\\(([^)]*)\\)").findAll(name).flatMap { it.groupValues[1].lowercase().split(',').map { t -> t.trim() } }.toList()

    /**
     * Sucht in der Namensliste des Servers den passendsten Eintrag zu einer ROM-Datei.
     * Alle Wörter des Titels müssen vorkommen; Region passt = Pluspunkt, Beta/Demo/Hack = Minuspunkt,
     * jedes zusätzliche Wort im Servernamen = Minuspunkt (höchstens 2 zusätzliche erlaubt).
     */
    fun bestMatch(romFileName: String, serverNames: List<String>): String? {
        val base = romFileName.substringBeforeLast('.', romFileName).trim()
        val want = words(titlePart(base))
        if (want.isEmpty()) return null
        val wantTags = tags(base)
        val region = wantTags.firstOrNull()
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (name in serverNames) {
            val have = words(titlePart(name))
            if (have.isEmpty() || !have.containsAll(want)) continue
            val extra = have.size - want.size
            if (extra > 2) continue
            var score = 100 - extra * 10
            if (extra == 0) score += 20
            val haveTags = tags(name)
            if (region != null && region in haveTags) score += 15
            if (BAD_TAGS.any { b -> haveTags.any { it.startsWith(b) } } && BAD_TAGS.none { b -> wantTags.any { it.startsWith(b) } }) score -= 40
            score -= name.length / 20
            if (score > bestScore) { bestScore = score; best = name }
        }
        return best
    }
}
