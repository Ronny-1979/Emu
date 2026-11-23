package de.ronny.poloretro

import android.content.Context
import android.content.SharedPreferences

/** Alle Einstellungen der App an einer Stelle. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("poloretro", Context.MODE_PRIVATE)

    /** 0 = automatisch (aus, sobald ein Controller verbunden ist), 1 = immer an, 2 = immer aus. */
    var overlayMode: Int
        get() = sp.getInt("overlay_mode", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("overlay_mode", v).apply()

    /** Deckkraft der Touch-Tasten in Prozent. */
    var overlayOpacity: Int
        get() = sp.getInt("overlay_opacity", 55).coerceIn(15, 100)
        set(v) = sp.edit().putInt("overlay_opacity", v.coerceIn(15, 100)).apply()

    /** Größe der Touch-Tasten in Prozent. */
    var overlaySize: Int
        get() = sp.getInt("overlay_size", 100).coerceIn(70, 140)
        set(v) = sp.edit().putInt("overlay_size", v.coerceIn(70, 140)).apply()

    /** 0 = Standard (weich), 1 = scharf, 2 = CRT, 3 = LCD. */
    var shader: Int
        get() = sp.getInt("shader", 1).coerceIn(0, 3)
        set(v) = sp.edit().putInt("shader", v.coerceIn(0, 3)).apply()

    /** N64-Auflösung: 0 = 640x480, 1 = 960x720, 2 = 1280x960. (Neuer Schlüssel, alte Werte galten für 320/640/960.) */
    var n64Resolution: Int
        get() = sp.getInt("n64_res3", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("n64_res3", v.coerceIn(0, 2)).apply()

    var n64FrameDuping: Boolean
        get() = sp.getBoolean("n64_framedup", true)
        set(v) = sp.edit().putBoolean("n64_framedup", v).apply()

    /** Fehlende Titelbilder automatisch aus dem Internet laden, wenn das Radio online ist. */
    var autoCovers: Boolean
        get() = sp.getBoolean("auto_covers", true)
        set(v) = sp.edit().putBoolean("auto_covers", v).apply()

    /** Controller rütteln lassen, wenn das Spiel es verlangt. */
    var rumble: Boolean
        get() = sp.getBoolean("rumble", true)
        set(v) = sp.edit().putBoolean("rumble", v).apply()

    /** N64-Steckmodul: 0 = Speicherkarte (Controller Pak), 1 = Rüttelmodul, 2 = keins. */
    var n64Pak: Int
        get() = sp.getInt("n64_pak", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("n64_pak", v.coerceIn(0, 2)).apply()

    /** Bildfilter nur für ein Spiel; -1 = es gilt die allgemeine Einstellung. */
    fun gameShader(system: RetroSystem, saveKey: String): Int =
        sp.getInt("gshader_${system.id}_$saveKey", -1).coerceIn(-1, 3)

    fun setGameShader(system: RetroSystem, saveKey: String, value: Int) {
        val key = "gshader_${system.id}_$saveKey"
        if (value < 0) sp.edit().remove(key).apply() else sp.edit().putInt(key, value.coerceIn(0, 3)).apply()
    }

    /** Mega Drive: 6-Tasten-Controller statt 3 Tasten. */
    var genesis6: Boolean
        get() = sp.getBoolean("genesis6", false)
        set(v) = sp.edit().putBoolean("genesis6", v).apply()

    /** Run-Ahead (Eingaben wirken ein Bild früher). Nur 2D-Konsolen, kostet Rechenleistung. */
    var runAhead: Boolean
        get() = sp.getBoolean("run_ahead", false)
        set(v) = sp.edit().putBoolean("run_ahead", v).apply()

    /** Automatische Bildverzögerung: Tasten werden kurz vor dem Bildtakt abgefragt. */
    var autoFrameDelay: Boolean
        get() = sp.getBoolean("auto_frame_delay", false)
        set(v) = sp.edit().putBoolean("auto_frame_delay", v).apply()

    /** Bilder pro Sekunde im Spiel einblenden (zur Fehlersuche). */
    var showFps: Boolean
        get() = sp.getBoolean("show_fps", false)
        set(v) = sp.edit().putBoolean("show_fps", v).apply()

    var audioFocus: Boolean
        get() = sp.getBoolean("audio_focus2", false)
        set(v) = sp.edit().putBoolean("audio_focus2", v).apply()

    /** Schneller Audiomodus (kleine Verzögerung). Manche Radios geben damit keinen Ton aus, darum standardmäßig aus. */
    var lowLatencyAudio: Boolean
        get() = sp.getBoolean("low_latency_audio", false)
        set(v) = sp.edit().putBoolean("low_latency_audio", v).apply()

    var soundOn: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    /** Beim Start eines Spiels automatisch dort weitermachen, wo man aufgehört hat. */
    var autoResume: Boolean
        get() = sp.getBoolean("auto_resume", true)
        set(v) = sp.edit().putBoolean("auto_resume", v).apply()

    fun mappingFor(system: RetroSystem): ControllerMapping =
        ControllerMapping.parse(sp.getString("map_" + system.id, null))

    fun setMapping(system: RetroSystem, mapping: ControllerMapping) {
        sp.edit().putString("map_" + system.id, mapping.serialize()).apply()
    }

    fun resetMapping(system: RetroSystem) {
        sp.edit().remove("map_" + system.id).apply()
    }

    // ---- Gemerkte Bibliotheksordner (z. B. PoloRetro-Ordner auf dem USB-Stick) ----

    fun libraryFolders(): List<String> =
        (sp.getString("library_folders", "") ?: "").split('\n').filter { it.isNotEmpty() }

    fun addLibraryFolder(path: String) {
        val list = libraryFolders().toMutableList()
        if (path !in list) list.add(path)
        sp.edit().putString("library_folders", list.joinToString("\n")).apply()
    }

    fun removeLibraryFolder(path: String) {
        val list = libraryFolders().filter { it != path }
        sp.edit().putString("library_folders", list.joinToString("\n")).apply()
    }

    // ---- Favoriten und zuletzt gespielt (nach Dateiname) ----

    fun favorites(): Set<String> = HashSet(sp.getStringSet("favorites", emptySet()) ?: emptySet())

    fun toggleFavorite(key: String): Boolean {
        val set = favorites().toMutableSet()
        val added = set.add(key)
        if (!added) set.remove(key)
        sp.edit().putStringSet("favorites", set).apply()
        return added
    }

    fun recents(): List<String> =
        (sp.getString("recents", "") ?: "").split('\n').filter { it.isNotEmpty() }

    fun addRecent(key: String) {
        val list = recents().toMutableList()
        list.remove(key)
        list.add(0, key)
        sp.edit().putString("recents", list.take(12).joinToString("\n")).apply()
    }
}
