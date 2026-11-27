package de.ronny.poloretro

/**
 * Erkennt an den ersten Bytes einer Sega-ROM, ob sie zum Mega Drive, Master System oder Game Gear gehört.
 * Die Dateiendung allein reicht nicht (z. B. ".bin" oder ".md" bei einem Master-System-Spiel).
 * Keine Android-Klassen, damit es testbar bleibt.
 */
object RomSniffer {
    /** So viele Bytes vom Dateianfang reichen für alle Prüfungen. */
    const val HEADER_BYTES = 0x8010

    private val TMR_SEGA = "TMR SEGA".toByteArray(Charsets.US_ASCII)
    private val SEGA = "SEGA".toByteArray(Charsets.US_ASCII)

    private fun matchAt(b: ByteArray, pos: Int, pattern: ByteArray): Boolean {
        if (pos < 0 || pos + pattern.size > b.size) return false
        for (i in pattern.indices) if (b[pos + i] != pattern[i]) return false
        return true
    }

    /** Ist es ein Mega-Drive-Header ("SEGA" bei 0x100, oder 0x101 bei " SEGA")? */
    private fun isMegaDrive(b: ByteArray): Boolean = matchAt(b, 0x100, SEGA) || matchAt(b, 0x101, SEGA)

    /** .smd-Dateien haben 512 Byte Vorspann (Kennung AA BB bei Byte 8/9); der Kopf liegt dann verschränkt. */
    private fun isSmdInterleaved(b: ByteArray): Boolean = b.size > 10 && b[8] == 0xAA.toByte() && b[9] == 0xBB.toByte()

    /** Master System oder Game Gear: "TMR SEGA" steht am Ende des 8/16/32-KB-Blocks. Das Byte danach verrät das Gerät. */
    private fun segaMasterFamily(b: ByteArray): RetroSystem? {
        for (pos in intArrayOf(0x7FF0, 0x3FF0, 0x1FF0)) {
            if (matchAt(b, pos, TMR_SEGA)) {
                val region = if (pos + 0xF < b.size) (b[pos + 0xF].toInt() ushr 4) and 0xF else 4
                return if (region in 5..7) RetroSystem.GAMEGEAR else RetroSystem.MASTERSYSTEM
            }
        }
        return null
    }

    /**
     * [byExtension] ist das System laut Endung. Gehört die Datei zur Sega-Familie, entscheidet der Inhalt;
     * erkennt man nichts, bleibt es bei der Endung. Alle anderen Konsolen bleiben unverändert.
     */
    fun refine(byExtension: RetroSystem, header: ByteArray): RetroSystem {
        if (byExtension != RetroSystem.MEGADRIVE && byExtension != RetroSystem.MASTERSYSTEM && byExtension != RetroSystem.GAMEGEAR) {
            return byExtension
        }
        if (isMegaDrive(header)) return RetroSystem.MEGADRIVE
        segaMasterFamily(header)?.let { return it }
        if (isSmdInterleaved(header)) return RetroSystem.MEGADRIVE
        return byExtension
    }
}
