package de.ronny.poloretro

/** Die unterstützten Konsolen mit Core-Datei und erlaubten Dateiendungen. */
enum class RetroSystem(
    val id: String,
    val label: String,
    val shortLabel: String,
    /** Dateiname des Cores in jniLibs/arm64-v8a. */
    val coreLib: String,
    val extensions: Set<String>,
    /** Ordnername auf thumbnails.libretro.com. */
    val thumbFolder: String,
) {
    SNES(
        "snes", "Super Nintendo", "SNES",
        "libsnes9x_libretro_android.so",
        setOf("sfc", "smc", "swc", "fig"),
        "Nintendo - Super Nintendo Entertainment System",
    ),
    MEGADRIVE(
        "md", "Sega Mega Drive", "Mega Drive",
        "libgenesis_plus_gx_libretro_android.so",
        // .bin ist bei Mega-Drive-Spielen üblich; N64-Dateien heißen nie .bin.
        setOf("md", "gen", "smd", "bin"),
        "Sega - Mega Drive - Genesis",
    ),
    N64(
        "n64", "Nintendo 64", "N64",
        "libmupen64plus_next_gles3_libretro_android.so",
        setOf("z64", "n64", "v64"),
        "Nintendo - Nintendo 64",
    ),
    NES(
        "nes", "Nintendo (NES)", "NES",
        "libfceumm_libretro_android.so",
        setOf("nes", "unf", "unif"),
        "Nintendo - Nintendo Entertainment System",
    ),
    GB(
        "gb", "Game Boy", "Game Boy",
        "libgambatte_libretro_android.so",
        setOf("gb"),
        "Nintendo - Game Boy",
    ),
    GBC(
        "gbc", "Game Boy Color", "GB Color",
        "libgambatte_libretro_android.so",
        setOf("gbc"),
        "Nintendo - Game Boy Color",
    ),
    GBA(
        "gba", "Game Boy Advance", "GBA",
        "libmgba_libretro_android.so",
        setOf("gba"),
        "Nintendo - Game Boy Advance",
    ),
    MASTERSYSTEM(
        "sms", "Sega Master System", "Master System",
        "libgenesis_plus_gx_libretro_android.so",
        setOf("sms"),
        "Sega - Master System - Mark III",
    ),
    GAMEGEAR(
        "gg", "Sega Game Gear", "Game Gear",
        "libgenesis_plus_gx_libretro_android.so",
        setOf("gg"),
        "Sega - Game Gear",
    );

    companion object {
        fun fromId(id: String?): RetroSystem? = values().firstOrNull { it.id == id }

        fun fromExtension(extension: String): RetroSystem? {
            val e = extension.lowercase()
            return values().firstOrNull { e in it.extensions }
        }
    }

    /** Name des Bilder-Repositorys bei GitHub (libretro-thumbnails): Leerzeichen werden zu Unterstrichen. */
    val thumbRepo: String get() = thumbFolder.replace(' ', '_')

}
