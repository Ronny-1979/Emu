package de.ronny.poloretro

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** Einstellungen: Touch-Tasten, Bild, N64, gemerkte Ordner, Sicherung der Spielstände, Protokoll. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var paths: AppPaths
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        paths = AppPaths(this)
        DiagLog.init(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
        }
        box = Ui.vertical(this).apply {
            val p = Ui.dp(this@SettingsActivity, 18)
            setPadding(p, Ui.dp(this@SettingsActivity, 10), p, p)
        }
        scroll.addView(box)
        setContentView(scroll)
        rebuild()
    }

    private fun rebuild() {
        box.removeAllViews()

        val header = Ui.horizontal(this)
        header.addView(Ui.label(this, "Einstellungen", 28f, Ui.ACCENT, true), Ui.weight())
        header.addView(Ui.button(this, "Fertig", onClick = { finish() }))
        box.addView(header)

        section("Bild und Ton")
        choice("Bildfilter", arrayOf("Standard (weich)", "Scharf", "CRT-Röhre", "LCD-Raster"), prefs.shader) { prefs.shader = it }
        toggle("Ton an", prefs.soundOn) { prefs.soundOn = it }
        toggle("Audiofokus anfordern (Standard: aus, wie bei Lemuroid)", prefs.audioFocus) { prefs.audioFocus = it }
        toggle("Schneller Audiomodus (bei Radio ohne Ton: aus lassen)", prefs.lowLatencyAudio) { prefs.lowLatencyAudio = it }
        toggle("Bilder pro Sekunde anzeigen (Fehlersuche)", prefs.showFps) { prefs.showFps = it }
        toggle("Controller rütteln (ab dem nächsten Spielstart)", prefs.rumble) { prefs.rumble = it }

        section("Spielen")
        toggle("Beim Start dort weitermachen, wo ich aufgehört habe", prefs.autoResume) { prefs.autoResume = it }
        toggle("Mega Drive: 6-Tasten-Controller", prefs.genesis6) { prefs.genesis6 = it }

        section("Eingabeverzögerung (gilt ab dem nächsten Spielstart)")
        toggle("Run-Ahead: Eingaben wirken ein Bild früher (nicht N64)", prefs.runAhead) { prefs.runAhead = it }

        section("Nintendo 64 (gilt ab dem nächsten Spielstart)")
        choice("Auflösung", arrayOf("640x480 (schnell)", "960x720", "1280x960 (am schärfsten, braucht viel Leistung)"), prefs.n64Resolution) { prefs.n64Resolution = it }
        toggle("Bildverdopplung (ruhigeres Bild bei niedriger Leistung)", prefs.n64FrameDuping) { prefs.n64FrameDuping = it }
        choice("Steckmodul im Controller", arrayOf("Speicherkarte (Controller Pak)", "Rüttelmodul (Rumble Pak)", "Keins"), prefs.n64Pak) { prefs.n64Pak = it }
        box.addView(note("Ein N64-Controller nimmt nur ein Modul auf: Speicherkarte oder Rüttelmodul. Spiele, die auf der Speicherkarte sichern, brauchen die Speicherkarte."))

        section("Titelbilder")
        toggle("Automatisch aus dem Internet laden (wenn online)", prefs.autoCovers) { prefs.autoCovers = it }
        box.addView(Ui.button(this, "Titelbilder jetzt laden", onClick = { downloadCovers() }), margins())
        box.addView(note("Quelle: thumbnails.libretro.com. Gefunden wird ein Bild nur, wenn der Dateiname dem üblichen Spielnamen entspricht, z. B. \"Super Mario World (USA).sfc\"."))

        section("Gemerkte ROM-Ordner")
        val folders = prefs.libraryFolders()
        if (folders.isEmpty()) {
            box.addView(note("Noch keine. Beim USB-Import kannst du \"Vom Stick spielen\" wählen, dann merkt sich die App den Ordner."))
        }
        for (f in folders) {
            val row = Ui.horizontal(this)
            row.addView(Ui.label(this, f, 15f, Ui.TEXT), Ui.weight())
            row.addView(Ui.button(this, "Entfernen", Ui.DANGER, onClick = {
                prefs.removeLibraryFolder(f)
                rebuild()
            }))
            box.addView(row, margins())
        }

        section("Spielstände sichern")
        box.addView(note("Spielstände liegen immer im Radio. Hier kannst du sie auf den USB-Stick sichern und von dort zurückholen."))
        box.addView(Ui.button(this, "Auf USB-Stick sichern", onClick = { backup() }), margins())
        box.addView(Ui.button(this, "Vom USB-Stick zurückholen", onClick = { restore() }), margins())

        section("Protokoll")
        box.addView(Ui.button(this, "Protokoll anzeigen", onClick = { showLog() }), margins())
        box.addView(Ui.button(this, "Protokoll teilen / per Mail senden", onClick = { shareLog() }), margins())
        box.addView(Ui.button(this, "Protokoll auf USB-Stick speichern", onClick = { exportLog() }), margins())
        box.addView(Ui.button(this, "Protokoll löschen", onClick = {
            DiagLog.clear()
            toast("Protokoll gelöscht")
        }), margins())

        section("Über")
        box.addView(note("Zockpit ${BuildConfig.VERSION_NAME}\nKerne: snes9x, Genesis Plus GX, Mupen64Plus-Next (libretro, GPL) über LibretroDroid."))
    }

    // ---------------------------------------------------------------- Bausteine

    private fun margins(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@SettingsActivity, 8)
        }

    private fun section(title: String) {
        box.addView(Ui.label(this, title, 18f, Ui.ACCENT, true).apply {
            setPadding(0, Ui.dp(this@SettingsActivity, 22), 0, Ui.dp(this@SettingsActivity, 2))
        })
    }

    private fun note(text: String): TextView = Ui.label(this, text, 14f, Ui.MUTED).apply {
        setPadding(0, Ui.dp(this@SettingsActivity, 4), 0, 0)
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
        var state = value
        lateinit var b: Button
        fun text() = title + ":  " + if (state) "An" else "Aus"
        b = Ui.button(this, text(), onClick = {
            state = !state
            onChange(state)
            b.text = text()
        })
        box.addView(b, margins())
    }

    private fun choice(title: String, options: Array<String>, value: Int, onChange: (Int) -> Unit) {
        var index = value.coerceIn(0, options.size - 1)
        lateinit var b: Button
        fun text() = title + ":  " + options[index]
        b = Ui.button(this, text(), onClick = {
            index = (index + 1) % options.size
            onChange(index)
            b.text = text()
        })
        box.addView(b, margins())
    }

    private fun slider(title: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) {
        val label = Ui.label(this, "$title: $value$unit", 16f, Ui.TEXT)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress
                    label.text = "$title: $v$unit"
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(label, margins())
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)))
    }

    // ---------------------------------------------------------------- Titelbilder

    private fun downloadCovers() {
        val progressText = TextView(this).apply {
            text = "Suche Spiele ..."
            textSize = 16f
            setTextColor(Ui.TEXT)
            setPadding(Ui.dp(this@SettingsActivity, 24), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 24), Ui.dp(this@SettingsActivity, 8))
        }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = Ui.dialog(this).setTitle("Titelbilder laden").setView(progressText).setCancelable(false)
            .setNegativeButton("Abbrechen") { _, _ -> cancel.set(true) }.show()
        thread {
            val result = try {
                val games = RomLibrary(this).scan()
                val downloader = CoverDownloader(paths)
                downloader.run(games, true, 500, { done, total, title ->
                    runOnUiThread { progressText.text = if (title.isEmpty()) "Fertig" else "$done von $total\n$title" }
                }, { cancel.get() })
            } catch (t: Throwable) {
                DiagLog.add("Titelbilder: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                dialog.dismiss()
                val msg = when {
                    result == null -> "Fehlgeschlagen. Details im Protokoll."
                    result.errors > 0 && result.found == 0 -> "Keine Verbindung zum Server. Ist das Radio online?"
                    else -> "${result.found} Bilder geladen, ${result.missing} beim Server nicht vorhanden" +
                        (if (result.errors > 0) ", ${result.errors} Netzfehler" else "") +
                        (if (result.cancelled) " (abgebrochen)" else "") + "."
                }
                toast(msg)
            }
        }
    }

    // ---------------------------------------------------------------- Sicherung

    private fun firstStickRoot(): File? {
        if (!UsbImport.hasAllFilesAccess()) {
            Ui.dialog(this)
                .setMessage("Für den USB-Stick braucht Zockpit die Berechtigung \"Zugriff auf alle Dateien\". Du kannst sie im Hauptmenü über \"USB-Import\" erteilen.")
                .setPositiveButton("OK", null)
                .show()
            return null
        }
        val root = UsbImport.candidateRoots(this).firstOrNull()
        if (root == null) toast("Kein USB-Stick gefunden. Bitte einstecken und erneut versuchen.")
        return root
    }

    private fun backup() {
        val root = firstStickRoot() ?: return
        val out = File(File(root, UsbImport.STICK_FOLDER), SaveBackup.FILE_NAME)
        toast("Sichere Spielstände ...")
        thread {
            val result = try {
                SaveBackup.export(paths.baseDir, paths.saveRoots.map { it.name }, out)
            } catch (t: Throwable) {
                DiagLog.add("Sicherung: ${t.javaClass.simpleName}: ${t.message}")
                -1
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    result < 0 -> toast("Sicherung fehlgeschlagen. Details im Protokoll.")
                    result == 0 -> toast("Es gibt noch keine Spielstände zum Sichern.")
                    else -> toast("$result Dateien gesichert: ${out.absolutePath}")
                }
            }
        }
    }

    private fun restore() {
        val root = firstStickRoot() ?: return
        val zip = File(File(root, UsbImport.STICK_FOLDER), SaveBackup.FILE_NAME)
        if (!zip.isFile) {
            toast("Keine Sicherung gefunden (${SaveBackup.FILE_NAME} im Ordner ${UsbImport.STICK_FOLDER}).")
            return
        }
        Ui.dialog(this)
            .setMessage("Spielstände vom Stick einspielen? Gleichnamige Spielstände im Radio werden überschrieben.")
            .setPositiveButton("Einspielen") { _, _ ->
                thread {
                    val result = try {
                        SaveBackup.restore(zip, paths.baseDir, paths.saveRoots.map { it.name }.toSet())
                    } catch (t: Throwable) {
                        DiagLog.add("Wiederherstellung: ${t.javaClass.simpleName}: ${t.message}")
                        -1
                    }
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (result < 0) toast("Einspielen fehlgeschlagen. Details im Protokoll.")
                        else toast("$result Dateien eingespielt.")
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------------------------------------------------------------- Protokoll

    private fun showLog() {
        val text = DiagLog.text().ifEmpty { "(leer)" }
        val view = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 8), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 8))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply {
            addView(view)
            post { fullScroll(View.FOCUS_DOWN) }
        }
        Ui.dialog(this).setTitle("Protokoll").setView(sv)
            .setPositiveButton("Schließen", null)
            .setNeutralButton("Teilen / Mail") { _, _ -> shareLog() }
            .show()
    }

    /** Öffnet die Android-Teilen-Auswahl (Mail, Nachrichten, ...) mit dem Protokoll als Text. */
    private fun shareLog() {
        val text = DiagLog.text().ifEmpty { "(leer)" }.takeLast(200_000)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Zockpit Protokoll (Version ${BuildConfig.VERSION_NAME})")
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(android.content.Intent.createChooser(send, "Protokoll senden"))
        } catch (_: Throwable) {
            toast("Keine App zum Teilen gefunden. Bitte \"auf USB-Stick speichern\" nutzen.")
        }
    }

    private fun exportLog() {
        val file = UsbImport.exportText(this, "PoloRetro_Protokoll.txt", DiagLog.text())
        toast(if (file != null) "Gespeichert: ${file.absolutePath}" else "Kein beschreibbarer USB-Stick gefunden.")
    }
}
