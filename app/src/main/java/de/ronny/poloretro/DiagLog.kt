package de.ronny.poloretro

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kleines Diagnoseprotokoll: im Speicher und in einer Datei, damit man es nach einem Absturz noch lesen kann. */
object DiagLog {
    private const val MAX_LINES = 600
    private const val MAX_FILE_BYTES = 256 * 1024L

    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "diag.log")
        try {
            if (f.exists() && f.length() > MAX_FILE_BYTES) f.delete()
            if (f.exists()) {
                f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            }
        } catch (_: Exception) {
            // Protokoll ist nur Beiwerk.
        }
        file = f
        add("--- Zockpit ${BuildConfig.VERSION_NAME} gestartet ---")
    }

    @Synchronized
    fun add(message: String) {
        val line = format.format(Date()) + "  " + message
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
            // Schreibfehler dürfen nie die App stören.
        }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (_: Exception) { }
    }
}
