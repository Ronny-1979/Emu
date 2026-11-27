package de.ronny.poloretro

import java.io.File
import java.util.zip.CRC32

/**
 * Liest die RetroArch-Datenbanken (.rdb) von libretro. Dort steht zu jeder bekannten ROM der Prüfsummen-Wert (CRC32)
 * und der richtige Name (No-Intro-Schreibweise, so heißen auch die Titelbilder). Keine Android-Klassen, damit es testbar bleibt.
 *
 * Aufbau: "RARCHDB\0", 8 Byte Länge des Datenteils, dann je Spiel ein MessagePack-Eintrag (Name, Größe, CRC, MD5, SHA1 ...).
 */
object RomDb {
    /** Alle Konsolen der App haben eine Datenbank (Dateiname = Ordnername der Titelbilder). */
    val SYSTEMS: Set<RetroSystem> = RetroSystem.values().toSet()

    /** Größere Dateien werden nicht geprüft. N64-Spiele sind bis 64 MB groß, alles andere höchstens ein paar MB. */
    fun maxBytes(system: RetroSystem): Long = if (system == RetroSystem.N64) 100L * 1024 * 1024 else 16L * 1024 * 1024

    /** CRC32 -> Spielname. Leer, wenn die Datei kein gültiges RDB ist. */
    fun parse(data: ByteArray): Map<Long, String> {
        val out = HashMap<Long, String>()
        if (data.size < 16 || String(data, 0, 7, Charsets.US_ASCII) != "RARCHDB") return out
        var end = 0L
        for (i in 8 until 16) end = (end shl 8) or (data[i].toLong() and 0xFF)
        val limit = if (end in 17..data.size.toLong()) end.toInt() else data.size
        val r = Reader(data, 16)
        try {
            while (r.p < limit) {
                val e = r.value()
                if (e is Map<*, *>) {
                    val name = e["name"] as? String
                    val crc = e["crc"] as? ByteArray
                    if (name != null && crc != null && crc.size == 4) {
                        var c = 0L
                        for (b in crc) c = (c shl 8) or (b.toLong() and 0xFF)
                        out.putIfAbsent(c, name)
                    }
                }
            }
        } catch (_: Exception) {
            // Beschädigtes Ende: was bis hier gelesen wurde, bleibt gültig.
        }
        return out
    }

    /** CRC32 der Datei, die ersten [skip] Bytes (Kopfteil) werden übersprungen. */
    fun crc32(file: File, skip: Long = 0): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            var left = skip
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) break
                left -= s
            }
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    /** Minimaler MessagePack-Leser (nur lesen, alle Typen, die in .rdb-Dateien vorkommen können). */
    private class Reader(val d: ByteArray, var p: Int) {
        private fun u8(): Int = d[p++].toInt() and 0xFF
        private fun u16(): Int = (u8() shl 8) or u8()
        private fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        private fun u64(): Long = (u32() shl 32) or u32()
        private fun str(n: Int): String { val s = String(d, p, n, Charsets.UTF_8); p += n; return s }
        private fun bin(n: Int): ByteArray { val b = d.copyOfRange(p, p + n); p += n; return b }
        private fun list(n: Int): List<Any?> = List(n) { value() }
        private fun map(n: Int): Map<Any?, Any?> {
            val m = HashMap<Any?, Any?>()
            repeat(n) { val k = value(); m[k] = value() }
            return m
        }

        fun value(): Any? {
            val b = u8()
            return when {
                b <= 0x7F -> b.toLong()
                b >= 0xE0 -> (b - 256).toLong()
                b in 0x80..0x8F -> map(b and 15)
                b in 0x90..0x9F -> list(b and 15)
                b in 0xA0..0xBF -> str(b and 31)
                else -> when (b) {
                    0xC0 -> null
                    0xC2 -> false
                    0xC3 -> true
                    0xC4 -> bin(u8())
                    0xC5 -> bin(u16())
                    0xC6 -> bin(u32().toInt())
                    0xCA -> { p += 4; null }
                    0xCB -> { p += 8; null }
                    0xCC -> u8().toLong()
                    0xCD -> u16().toLong()
                    0xCE -> u32()
                    0xCF -> u64()
                    0xD0 -> u8().toByte().toLong()
                    0xD1 -> u16().toShort().toLong()
                    0xD2 -> u32().toInt().toLong()
                    0xD3 -> u64()
                    0xD9 -> str(u8())
                    0xDA -> str(u16())
                    0xDB -> str(u32().toInt())
                    0xDC -> list(u16())
                    0xDD -> list(u32().toInt())
                    0xDE -> map(u16())
                    0xDF -> map(u32().toInt())
                    else -> throw IllegalArgumentException("Unbekannter Typ $b")
                }
            }
        }
    }
}

/** Erkennung einer ROM-Datei über die Datenbank. */
object RomId {
    private val SEGA = setOf(RetroSystem.MEGADRIVE, RetroSystem.MASTERSYSTEM, RetroSystem.GAMEGEAR)

    /**
     * Richtiger Name. [canonicalExt] ist true, wenn die Datei auf die übliche Endung der Konsole umbenannt werden darf
     * (glatte Sega-ROMs); sonst bleibt die Endung, weil sie zum Inhalt gehört (.smd, .sfc/.smc, N64-Byte-Reihenfolge).
     */
    class Hit(val name: String, val canonicalExt: Boolean)

    fun identify(file: File, db: Map<Long, String>, system: RetroSystem): Hit? {
        val size = file.length()
        if (!file.isFile || size <= 0 || size > RomDb.maxBytes(system)) return null
        // 1. Datei so, wie sie ist (No-Intro-Prüfsummen; bei N64 stehen alle drei Byte-Reihenfolgen in der Datenbank).
        db[RomDb.crc32(file)]?.let { return Hit(it, system in SEGA) }
        // 2. Varianten mit Kopfteil: Die Datenbank kennt nur die Prüfsumme ohne den Vorspann.
        when (system) {
            RetroSystem.SNES ->
                // Kopierer-Vorspann (512 Byte): Dateigröße ist dann 512 mehr als ein Vielfaches von 1024.
                if (size % 1024 == 512L) db[RomDb.crc32(file, 512)]?.let { return Hit(it, false) }
            RetroSystem.NES ->
                if (size > 16 && hasInesHeader(file)) db[RomDb.crc32(file, 16)]?.let { return Hit(it, false) }
            RetroSystem.MEGADRIVE -> {
                val crc = if (size <= 8L * 1024 * 1024) smdCrc(file) else null
                if (crc != null) db[crc]?.let { return Hit(it, false) }
            }
            else -> {}
        }
        return null
    }

    private fun hasInesHeader(file: File): Boolean = try {
        file.inputStream().use { input ->
            val b = ByteArray(4)
            input.read(b) == 4 && b[0] == 'N'.code.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'S'.code.toByte() && b[3] == 0x1A.toByte()
        }
    } catch (_: Exception) {
        false
    }

    /** CRC32 einer .smd-Datei nach dem Entschränken, oder null, wenn die Datei kein .smd-Format hat. */
    fun smdCrc(file: File): Long? = smdCrc(file.readBytes())

    fun smdCrc(raw: ByteArray): Long? {
        if (raw.size < 512 + 16384 || raw[8] != 0xAA.toByte() || raw[9] != 0xBB.toByte()) return null
        val body = raw.size - 512
        if (body % 16384 != 0) return null
        val out = ByteArray(body)
        var blk = 0
        while (blk < body) {
            val src = 512 + blk
            for (j in 0 until 8192) {
                out[blk + 2 * j + 1] = raw[src + j]
                out[blk + 2 * j] = raw[src + 8192 + j]
            }
            blk += 16384
        }
        val crc = CRC32()
        crc.update(out)
        return crc.value
    }
}
