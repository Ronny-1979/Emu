package de.ronny.poloretro

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.VibrationEffect
import android.view.PixelCopy
import android.widget.EditText
import kotlin.concurrent.thread
import com.swordfish.libretrodroid.RumbleEvent
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Das laufende Spiel: Emulator-Ansicht, Touch-Tasten, Bluetooth-Controller und Pausenmenü. */
class GameActivity : ComponentActivity(), TouchOverlayView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var paths: AppPaths
    private lateinit var system: RetroSystem
    private lateinit var game: Game
    private lateinit var retroView: GLRetroView
    private lateinit var overlay: TouchOverlayView
    private lateinit var menuLayer: FrameLayout
    private lateinit var notice: TextView
    private var fpsLabel: TextView? = null
    private var fpsFrames = 0
    private var fpsLastNs = 0L
    private var fpsLogTicks = 0

    private val handler = Handler(Looper.getMainLooper())
    private var mapping = ControllerMapping.default()
    private var menuOpen = false
    private var started = false
    private var finishingByMenu = false

    // Menü-Beschriftungen, die sich ändern
    private lateinit var filterButton: Button
    private lateinit var soundButton: Button
    private lateinit var aspectButton: Button
    private val saveButtons = ArrayList<Button>()
    private val loadButtons = ArrayList<Button>()
    private lateinit var firstMenuButton: Button

    // Eingabezustand je Controller-Anschluss (0..3)
    private class PadState {
        var hatX = 0f
        var hatY = 0f
        var lx = 0f
        var ly = 0f
        var rx = 0f
        var ry = 0f
        val axisKeys = AxisKeys()
    }

    private val pads = Array(4) { PadState() }
    private val heldRetro = HashSet<Int>()
    private val padDown = HashSet<Int>()
    private var lastMenuHatY = 0f

    private var audioRequest: AudioFocusRequest? = null
    private var inputListener: InputManager.InputDeviceListener? = null

    private val comboRunnable = Runnable { openMenu() }
    private val hideNotice = Runnable { notice.visibility = View.GONE }

    // ---------------------------------------------------------------- Start

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        prefs = Prefs(this)
        paths = AppPaths(this)

        val path = intent.getStringExtra(EXTRA_PATH)
        val sys = RetroSystem.fromId(intent.getStringExtra(EXTRA_SYSTEM))
        if (path == null || sys == null) {
            finish()
            return
        }
        system = sys
        val original = File(path)
        game = Game(original, system, intent.getStringExtra(EXTRA_TITLE) ?: RomNames.title(original.name))

        val romFile = prepareRom(original)
        if (romFile == null) {
            DiagLog.add("ROM nicht lesbar: $path")
            showFatal("Die Datei konnte nicht geöffnet werden:\n${original.name}")
            return
        }

        mapping = prefs.mappingFor(system)
        DiagLog.add("Starte \"${game.title}\" (${system.label}), Core ${system.coreLib}, Datei ${romFile.name} (${romFile.length() / 1024} KB)")

        val data = GLRetroViewData(this).apply {
            coreFilePath = system.coreLib
            gameFilePath = romFile.absolutePath
            systemDirectory = paths.systemDir.absolutePath
            savesDirectory = paths.savesDir(system).absolutePath
            variables = coreVariables()
            saveRAMState = readBytes(paths.sramFile(game))
            shader = shaderFor(effectiveShader())
            rumbleEventsEnabled = prefs.rumble
            preferLowLatencyAudio = prefs.lowLatencyAudio
            skipDuplicateFrames = false
        }
        retroView = GLRetroView(this, data)
        // Nur 2D-Konsolen: Der N64-Kern zeichnet mit der Grafikkarte, dort geht Run-Ahead nicht.
        retroView.runAhead = prefs.runAhead && system != RetroSystem.N64
        // Zurückspulen mit L2: nicht beim N64 (dort ist L2 die Z-Taste und der Kern zeichnet mit der Grafikkarte).
        rewindOn = prefs.rewind && system != RetroSystem.N64
        retroView.rewind = rewindOn
        applyAspectMode()
        lifecycle.addObserver(retroView)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(retroView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        overlay = TouchOverlayView(this).apply {
            listener = this@GameActivity
            menuOnly = true
            opacity = 0.45f
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        notice = Ui.label(this, "", 18f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC12161B.toInt())
                cornerRadius = Ui.dp(this@GameActivity, 14).toFloat()
            }
            setPadding(Ui.dp(this@GameActivity, 18), Ui.dp(this@GameActivity, 10), Ui.dp(this@GameActivity, 18), Ui.dp(this@GameActivity, 10))
            visibility = View.GONE
        }
        val noticeLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        noticeLp.topMargin = Ui.dp(this, 64)
        root.addView(notice, noticeLp)

        if (prefs.showFps) {
            fpsLabel = Ui.label(this, "", 16f, Ui.TEXT, true).apply {
                setBackgroundColor(0x99000000.toInt())
                setPadding(Ui.dp(this@GameActivity, 8), Ui.dp(this@GameActivity, 3), Ui.dp(this@GameActivity, 8), Ui.dp(this@GameActivity, 3))
            }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            lp.topMargin = Ui.dp(this, 6)
            lp.leftMargin = Ui.dp(this, 6)
            root.addView(fpsLabel, lp)
        }
        logDisplayRate()

        menuLayer = buildMenu()
        menuLayer.visibility = View.GONE
        root.addView(menuLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        hideSystemUi()
        // Stick/Steuerkreuz-Bewegungen sonst erst mit dem nächsten Bildtakt zustellen lassen (Android 12+).
        root.post {
            try {
                if (Build.VERSION.SDK_INT >= 31) root.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK)
            } catch (t: Throwable) {
                DiagLog.add("Ungepufferte Eingabe: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        updateOverlayVisibility()
        registerInputListener()
        logAudioState("Start")
        requestAudioFocus()

        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { code -> onCoreError(code) }
        }
        if (prefs.rumble) {
            lifecycleScope.launch {
                retroView.getRumbleEvents().collect { onRumble(it) }
            }
        }
        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            onFirstFrame()
        }
        if (prefs.showFps) startFpsCounter()
        showNotice("Menü: Zurück-Taste oder Select+Start halten", 3500)
    }

    @Suppress("DEPRECATION")
    private fun displayHz(): Float = try {
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
    } catch (t: Throwable) {
        0f
    }

    private fun logDisplayRate() {
        DiagLog.add("Bildschirm: ${"%.1f".format(displayHz())} Hz")
    }

    /** Zählt die gezeichneten Bilder und zeigt sie jede Sekunde an. Im Protokoll steht alle 10 Sekunden ein Wert. */
    private fun startFpsCounter() {
        fpsLastNs = System.nanoTime()
        lifecycleScope.launch {
            retroView.getGLRetroEvents().collect { if (it is GLRetroView.GLRetroEvents.FrameRendered) fpsFrames++ }
        }
        val tick = object : Runnable {
            override fun run() {
                val now = System.nanoTime()
                val secs = (now - fpsLastNs) / 1_000_000_000.0
                val fps = if (secs > 0) fpsFrames / secs else 0.0
                fpsFrames = 0
                fpsLastNs = now
                fpsLabel?.text = "%.0f FPS (Anzeige %.0f Hz)".format(fps, displayHz())
                if (!menuOpen && ++fpsLogTicks % 10 == 0) DiagLog.add("Bilder pro Sekunde: ${"%.1f".format(fps)}")
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(tick, 1000)
    }

    /** ZIP wird ausgepackt, alles andere direkt benutzt. Null, wenn die Datei nicht nutzbar ist. */
    private fun prepareRom(file: File): File? {
        if (!file.exists() || !file.canRead()) return null
        if (!RomNames.isZip(file.name)) return withRightExtension(file)
        val dir = paths.tempRomDir
        dir.listFiles()?.forEach { it.delete() }
        val extracted = ZipRom.extract(file, dir) ?: return null
        return if (extracted.second == system) extracted.first else null
    }

    /**
     * Der Sega-Core (Genesis Plus GX) erkennt Master System und Game Gear an den letzten drei Zeichen des Dateipfads.
     * Passt die Endung nicht zur erkannten Konsole (z. B. ein Master-System-Spiel als ".bin"), geht eine Kopie mit richtiger Endung an den Core.
     */
    private fun withRightExtension(file: File): File {
        val ext = RomNames.extension(file.name)
        if (ext in system.extensions) return file
        if (system != RetroSystem.MASTERSYSTEM && system != RetroSystem.GAMEGEAR && system != RetroSystem.MEGADRIVE) return file
        return try {
            val dir = paths.tempRomDir
            dir.listFiles()?.forEach { it.delete() }
            val base = file.name.substringBeforeLast('.', file.name).replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
            val target = File(dir, base + "." + system.extensions.first())
            file.copyTo(target, overwrite = true)
            DiagLog.add("Endung \".$ext\" passt nicht zu ${system.label}, Kopie als ${target.name}")
            target
        } catch (_: Exception) {
            file
        }
    }

    private fun coreVariables(): Array<Variable> {
        if (system != RetroSystem.N64) return arrayOf()
        val res = arrayOf("640x480", "960x720", "1280x960")[prefs.n64Resolution]
        val pak = arrayOf("memory", "rumble", "none")[prefs.n64Pak]
        return arrayOf(
            Variable("mupen64plus-pak1", pak),
            Variable("mupen64plus-43screensize", res),
            Variable("mupen64plus-FrameDuping", if (prefs.n64FrameDuping) "True" else "False"),
        )
    }

    private fun effectiveShader(): Int {
        val own = prefs.gameShader(system, game.saveKey)
        return if (own >= 0) own else prefs.shader
    }

    private fun shaderFor(index: Int): ShaderConfig = when (index) {
        1 -> ShaderConfig.Sharp
        2 -> ShaderConfig.CRT
        3 -> ShaderConfig.LCD
        else -> ShaderConfig.Default
    }

    private fun readBytes(file: File): ByteArray? = try {
        if (file.exists() && file.length() > 0) file.readBytes() else null
    } catch (_: Exception) {
        null
    }

    private suspend fun onFirstFrame() {
        started = true
        playStart = android.os.SystemClock.elapsedRealtime()
        if (rewindOn) DiagLog.add("Zurückspulen: an (L2 halten, etwa 10 Sekunden)")
        if (prefs.runAhead) {
            val ra = when {
                !prefs.runAhead -> "aus"
                system == RetroSystem.N64 -> "für N64 nicht möglich"
                retroView.isRunAheadActive() -> "aktiv"
                else -> "nicht möglich (dieser Kern kann keine Zustände sichern)"
            }
            DiagLog.add("Eingabeverzögerung: Run-Ahead $ra")
        }
        applyAllCheats()
        applyAudio()
        if (system == RetroSystem.MEGADRIVE && prefs.genesis6) {
            try {
                val ctrl = retroView.getControllers().getOrNull(0)?.firstOrNull { it.description == "MD Joypad 6 Button" }
                if (ctrl != null) retroView.setControllerType(0, ctrl.id)
            } catch (t: Throwable) {
                DiagLog.add("6-Tasten-Controller: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        if (prefs.autoResume) {
            val file = paths.autoStateFile(game)
            if (file.exists() && file.length() > 0) {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        retroView.unserializeState(file.readBytes())
                    } catch (t: Throwable) {
                        DiagLog.add("Fortsetzen: ${t.javaClass.simpleName}: ${t.message}")
                        false
                    }
                }
                DiagLog.add("Fortsetzen: " + if (ok) "ok" else "nicht möglich")
                if (ok) showNotice("Spiel fortgesetzt", 1800)
            }
        }
    }

    // ---------------------------------------------------------------- Fehler

    private fun onCoreError(code: Int) {
        val text = when (code) {
            GLRetroView.ERROR_LOAD_LIBRARY -> "Der Emulator-Kern konnte nicht geladen werden."
            GLRetroView.ERROR_LOAD_GAME -> "Das Spiel konnte nicht geladen werden. Ist die Datei in Ordnung?"
            GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "Die Grafik des Radios ist für diesen Emulator nicht kompatibel."
            GLRetroView.ERROR_SERIALIZATION -> "Speichern oder Laden des Spielstands ist bei diesem Spiel nicht möglich."
            GLRetroView.ERROR_CHEAT -> "Cheat konnte nicht gesetzt werden."
            else -> "Unbekannter Emulator-Fehler."
        }
        DiagLog.add("Core-Fehler $code: $text")
        if (code == GLRetroView.ERROR_SERIALIZATION || code == GLRetroView.ERROR_CHEAT) {
            showNotice(text, 3000)
        } else {
            showFatal("$text\n(Fehlercode $code)")
        }
    }

    private fun showFatal(message: String) {
        Ui.dialog(this)
            .setTitle("Zockpit")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Zurück") { _, _ -> finish() }
            .show()
    }

    // ---------------------------------------------------------------- Oberfläche und Menü

    private fun showNotice(text: String, millis: Long) {
        notice.text = text
        notice.visibility = View.VISIBLE
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, millis)
    }

    private fun buildMenu(): FrameLayout {
        val layer = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
        }
        val card = Ui.vertical(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.BG)
                cornerRadius = Ui.dp(this@GameActivity, 18).toFloat()
            }
            setPadding(Ui.dp(this@GameActivity, 22), Ui.dp(this@GameActivity, 16), Ui.dp(this@GameActivity, 22), Ui.dp(this@GameActivity, 18))
        }
        card.addView(Ui.label(this, game.title, 22f, Ui.ACCENT, true).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(Ui.label(this, system.label + "  ·  Pause", 14f, Ui.MUTED))

        val columns = Ui.horizontal(this).apply { gravity = Gravity.TOP }
        val left = Ui.vertical(this)
        val right = Ui.vertical(this)
        val gap = Ui.dp(this, 8)

        fun add(column: LinearLayout, b: View) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = gap
            column.addView(b, lp)
        }

        fun heading(column: LinearLayout, text: String) {
            column.addView(Ui.label(this, text, 12f, Ui.MUTED, true).apply {
                setPadding(Ui.dp(this@GameActivity, 4), Ui.dp(this@GameActivity, 12), 0, 0)
            })
        }

        firstMenuButton = Ui.button(this, "Weiterspielen", Ui.ACCENT, 0xFF12161B.toInt(), onClick = { closeMenu() })
        add(left, firstMenuButton)
        heading(left, "SPIELSTAND (● = belegt)")

        fun slotRow(title: String, list: MutableList<Button>, action: (Int) -> Unit): LinearLayout {
            val row = Ui.horizontal(this)
            row.addView(Ui.label(this, title, 15f, Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(this, 96), ViewGroup.LayoutParams.WRAP_CONTENT))
            for (slot in 1..AppPaths.SLOTS) {
                val b = Ui.button(this, "$slot", onClick = { action(slot) })
                list.add(b)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (slot > 1) lp.leftMargin = Ui.dp(this, 6)
                row.addView(b, lp)
            }
            return row
        }
        add(left, slotRow("Speichern", saveButtons) { saveSlot(it) })
        add(left, slotRow("Laden", loadButtons) { loadSlot(it) })
        heading(left, "SPIEL")
        add(left, Ui.button(this, "Spiel neu starten", onClick = { confirmReset() }))
        add(left, Ui.button(this, "Spiel beenden", Ui.DANGER, onClick = { exitGame() }))

        filterButton = Ui.button(this, "", onClick = { cycleFilter() })
        soundButton = Ui.button(this, "", onClick = { toggleSound() })
        aspectButton = Ui.button(this, "", onClick = { cycleAspect() })
        heading(right, "BILD UND TON")
        add(right, filterButton)
        add(right, aspectButton)
        add(right, soundButton)
        heading(right, "STEUERUNG")
        add(right, Ui.button(this, "Tasten belegen (Controller)", onClick = {
            startActivity(Intent(this, ControllerActivity::class.java).putExtra(ControllerActivity.EXTRA_SYSTEM, system.id))
        }))
        if (rewindOn) {
            add(right, Ui.label(this, "Zurückspulen: im Spiel L2 halten.", 13f, Ui.MUTED).apply {
                setPadding(Ui.dp(this@GameActivity, 4), Ui.dp(this@GameActivity, 2), 0, 0)
            })
        }
        heading(right, "EXTRAS")
        add(right, Ui.button(this, "Cheats", onClick = { showCheats() }))
        if (system == RetroSystem.N64) {
            add(right, Ui.label(this, "N64-Auflösung, Bildverdopplung und Steckmodul (Speicherkarte/Rüttelmodul) stellst du in den Einstellungen ein (gilt ab dem nächsten Start).", 13f, Ui.MUTED).apply {
                setPadding(0, Ui.dp(this@GameActivity, 6), 0, 0)
            })
        }

        columns.addView(left, LinearLayout.LayoutParams(Ui.dp(this, 360), ViewGroup.LayoutParams.WRAP_CONTENT))
        columns.addView(Ui.spacer(this, 18))
        columns.addView(right, LinearLayout.LayoutParams(Ui.dp(this, 340), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(columns)
        layer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        refreshMenuTexts()
        return layer
    }

    private fun refreshMenuTexts() {
        val filters = arrayOf("Standard (weich)", "Scharf", "CRT-Röhre", "LCD-Raster")
        val own = prefs.gameShader(system, game.saveKey)
        filterButton.text = "Bildfilter (dieses Spiel): " +
            if (own < 0) "wie Einstellungen (${filters[prefs.shader]})" else filters[own]
        aspectButton.text = "Bildformat: " + aspectNames[prefs.aspectMode]
        soundButton.text = "Ton: " + if (prefs.soundOn) "An" else "Aus"
        for (slot in 1..AppPaths.SLOTS) {
            val has = paths.slotStateFile(game, slot).let { it.exists() && it.length() > 0 }
            saveButtons[slot - 1].text = if (has) "$slot ●" else "$slot"
            loadButtons[slot - 1].text = if (has) "$slot ●" else "$slot"
            loadButtons[slot - 1].isEnabled = has
            loadButtons[slot - 1].alpha = if (has) 1f else 0.4f
            setThumb(loadButtons[slot - 1], if (has) paths.slotThumbFile(game, slot) else null)
        }
    }

    private fun cycleFilter() {
        // Reihenfolge: wie Einstellungen (-1), dann die vier Filter. Gilt nur für dieses Spiel.
        val next = prefs.gameShader(system, game.saveKey) + 1
        prefs.setGameShader(system, game.saveKey, if (next > 3) -1 else next)
        retroView.shader = shaderFor(effectiveShader())
        refreshMenuTexts()
    }

    private fun setThumb(button: Button, file: File?) {
        var drawable: BitmapDrawable? = null
        if (file != null && file.isFile) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    drawable = BitmapDrawable(resources, bmp).apply {
                        val h = Ui.dp(this@GameActivity, 40)
                        setBounds(0, 0, h * bmp.width / bmp.height, h)
                    }
                }
            } catch (_: Throwable) {
                drawable = null
            }
        }
        button.setCompoundDrawables(null, drawable, null, null)
    }

    /** Standbild der aktuellen Szene als Vorschau zum Speicherplatz (das Menü ist dabei nicht im Bild). */
    private fun captureThumb(target: File) {
        val w = retroView.width
        val h = retroView.height
        if (w <= 0 || h <= 0) return
        try {
            val bmp = Bitmap.createBitmap(320, (320f * h / w).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            PixelCopy.request(retroView, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    thread {
                        try {
                            target.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                        } catch (_: Exception) {
                            // Vorschau ist nur Zugabe
                        }
                        runOnUiThread { if (!isFinishing) refreshMenuTexts() }
                    }
                }
            }, handler)
        } catch (t: Throwable) {
            DiagLog.add("Vorschaubild: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- Cheats

    private var cheats: MutableList<Cheat> = mutableListOf()

    private fun applyCheat(index: Int, cheat: Cheat) {
        try {
            retroView.setCheat(index, cheat.enabled, cheat.code, !menuOpen)
        } catch (t: Throwable) {
            DiagLog.add("Cheat: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun applyAllCheats() {
        cheats = CheatStore.load(paths.cheatFile(game)).toMutableList()
        cheats.forEachIndexed { i, c -> if (c.enabled) applyCheat(i, c) }
        if (cheats.any { it.enabled }) DiagLog.add("Cheats aktiv: ${cheats.count { it.enabled }}")
    }

    private fun saveCheats() {
        try {
            CheatStore.save(paths.cheatFile(game), cheats)
        } catch (t: Throwable) {
            DiagLog.add("Cheats speichern: ${t.javaClass.simpleName}: ${t.message}")
            showNotice("Cheats konnten nicht gespeichert werden", 2500)
        }
    }

    private fun showCheats() {
        val names = cheats.map { it.name }.toTypedArray()
        val checked = BooleanArray(cheats.size) { cheats[it].enabled }
        val builder = Ui.dialog(this)
            .setTitle(if (cheats.isEmpty()) "Cheats (noch keine eingetragen)" else "Cheats – Haken = an")
            .setNeutralButton("Neuer Cheat") { _, _ -> newCheat() }
        if (cheats.isNotEmpty()) {
            builder.setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Übernehmen") { _, _ ->
                    for (i in cheats.indices) {
                        if (cheats[i].enabled != checked[i]) {
                            cheats[i] = cheats[i].copy(enabled = checked[i])
                            applyCheat(i, cheats[i])
                        }
                    }
                    saveCheats()
                    showNotice("Cheats übernommen", 1500)
                }
                .setNegativeButton("Löschen …") { _, _ -> deleteCheat() }
        } else {
            builder.setNegativeButton("Schließen", null)
        }
        builder.show()
    }

    private fun newCheat() {
        val pad = Ui.dp(this, 20)
        val box = Ui.vertical(this).apply { setPadding(pad, Ui.dp(this@GameActivity, 8), pad, 0) }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            isSingleLine = true
        }
        val name = field("Name (z. B. Unendlich Leben)")
        val code = field("Code (Game Genie / Action Replay)")
        box.addView(name)
        box.addView(code)
        Ui.dialog(this)
            .setTitle("Neuer Cheat")
            .setView(box)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val c = code.text.toString().trim()
                if (c.isEmpty()) {
                    showNotice("Kein Code eingegeben", 2000)
                } else {
                    val cheat = Cheat(name.text.toString().trim().ifEmpty { c }, c, true)
                    cheats.add(cheat)
                    applyCheat(cheats.size - 1, cheat)
                    saveCheats()
                    showNotice("Cheat eingeschaltet", 1800)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun deleteCheat() {
        if (cheats.isEmpty()) return
        Ui.dialog(this)
            .setTitle("Welchen Cheat löschen?")
            .setItems(cheats.map { it.name }.toTypedArray()) { _, which ->
                // Alle Plätze ausschalten und die übrigen neu eintragen, damit die Nummern stimmen.
                cheats.forEachIndexed { i, c -> applyCheat(i, c.copy(enabled = false)) }
                cheats.removeAt(which)
                cheats.forEachIndexed { i, c -> if (c.enabled) applyCheat(i, c) }
                saveCheats()
                showNotice("Cheat gelöscht", 1500)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------------------------------------------------------------- Rütteln

    @Suppress("DEPRECATION")
    private fun vibratorOf(dev: InputDevice): android.os.Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) dev.vibratorManager.defaultVibrator else dev.vibrator

    private fun gamepadForPort(port: Int): InputDevice? =
        InputDevice.getDeviceIds().asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { isGamepad(it) && portOf(it) == port }

    private fun onRumble(e: RumbleEvent) {
        val strength = maxOf(e.strengthWeak, e.strengthStrong).coerceIn(0f, 1f)
        try {
            val vib = gamepadForPort(e.port)?.let { vibratorOf(it) } ?: return
            if (strength <= 0f || menuOpen) {
                vib.cancel()
            } else if (vib.hasAmplitudeControl()) {
                vib.vibrate(VibrationEffect.createOneShot(60_000, (strength * 255).toInt().coerceIn(1, 255)))
            } else {
                vib.vibrate(VibrationEffect.createOneShot(60_000, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Throwable) {
            // Rütteln ist nur Zugabe
        }
    }

    private fun stopRumble() {
        try {
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (isGamepad(dev)) vibratorOf(dev)?.cancel()
            }
        } catch (_: Throwable) {
            // ignorieren
        }
    }

    private fun toggleSound() {
        prefs.soundOn = !prefs.soundOn
        refreshMenuTexts()
    }

    private fun applyAudio() {
        retroView.audioEnabled = prefs.soundOn && !menuOpen
    }

    private fun confirmReset() {
        Ui.dialog(this)
            .setMessage("Das Spiel wirklich neu starten? Nicht gespeicherter Fortschritt geht verloren.")
            .setPositiveButton("Neu starten") { _, _ ->
                try {
                    retroView.reset(false)
                    closeMenu()
                } catch (t: Throwable) {
                    DiagLog.add("Reset: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun exitGame() {
        finishingByMenu = true
        finish()
    }

    // ---------------------------------------------------------------- Pause

    private fun openMenu() {
        if (menuOpen || !started) return
        flushPlayTime()
        menuOpen = true
        playStart = 0L
        handler.removeCallbacks(comboRunnable)
        stopRumble()
        releaseAllInputs()
        refreshMenuTexts()
        menuLayer.visibility = View.VISIBLE
        pauseRender()
        firstMenuButton.requestFocus()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        if (started) playStart = android.os.SystemClock.elapsedRealtime()
        menuLayer.visibility = View.GONE
        retroView.onResume()
        applyAudio()
    }

    private fun pauseRender() {
        retroView.audioEnabled = false
        retroView.onPause()
    }

    private fun saveSlot(slot: Int) {
        try {
            val bytes = retroView.serializeState(false)
            if (bytes.isEmpty()) {
                showNotice("Speichern bei diesem Spiel nicht möglich", 2500)
                return
            }
            writeAtomic(paths.slotStateFile(game, slot), bytes)
            paths.slotThumbFile(game, slot).delete()
            captureThumb(paths.slotThumbFile(game, slot))
            DiagLog.add("Spielstand $slot gespeichert (${bytes.size / 1024} KB)")
            showNotice("In Platz $slot gespeichert", 1800)
            refreshMenuTexts()
        } catch (t: Throwable) {
            DiagLog.add("Speichern: ${t.javaClass.simpleName}: ${t.message}")
            showNotice("Speichern fehlgeschlagen", 2500)
        }
    }

    private fun loadSlot(slot: Int) {
        try {
            val file = paths.slotStateFile(game, slot)
            if (!file.exists()) return
            val ok = retroView.unserializeState(file.readBytes(), false)
            DiagLog.add("Spielstand $slot laden: " + if (ok) "ok" else "fehlgeschlagen")
            if (ok) {
                closeMenu()
                showNotice("Platz $slot geladen", 1800)
            } else {
                showNotice("Laden fehlgeschlagen", 2500)
            }
        } catch (t: Throwable) {
            DiagLog.add("Laden: ${t.javaClass.simpleName}: ${t.message}")
            showNotice("Laden fehlgeschlagen", 2500)
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(bytes)
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) throw java.io.IOException("Umbenennen fehlgeschlagen")
    }

    /** Sichert Spielstand (SRAM) und automatischen Fortsetzungsstand. Das Rendern ist dabei schon angehalten. */
    private fun saveAuto() {
        if (!started) return
        try {
            val sram = retroView.serializeSRAM(false)
            if (sram.isNotEmpty()) writeAtomic(paths.sramFile(game), sram)
        } catch (t: Throwable) {
            DiagLog.add("SRAM sichern: ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            val state = retroView.serializeState(false)
            if (state.isNotEmpty()) writeAtomic(paths.autoStateFile(game), state)
        } catch (t: Throwable) {
            DiagLog.add("Zustand sichern: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        flushPlayTime()
        playStart = 0L
        stopRumble()
        releaseAllInputs()
        saveAuto()
    }

    override fun onResume() {
        super.onResume()
        if (!::game.isInitialized) return
        mapping = prefs.mappingFor(system)
        if (started && !menuOpen) playStart = android.os.SystemClock.elapsedRealtime()
        hideSystemUi()
        updateOverlayVisibility()
        if (menuOpen) handler.post { if (menuOpen) pauseRender() } else handler.post { applyAudio() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        inputListener?.let { (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(it) }
        audioRequest?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- System

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun logAudioState(where: String) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString(",") { it.type.toString() }
            DiagLog.add(
                "Ton ($where): Medienlautstärke ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, " +
                    "Spiel-Ton ${if (prefs.soundOn) "an" else "aus"}, schneller Modus ${if (prefs.lowLatencyAudio) "an" else "aus"}, Ausgänge [$outs]",
            )
        } catch (t: Throwable) {
            DiagLog.add("Ton-Status: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun requestAudioFocus() {
        if (!prefs.audioFocus) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener { change ->
                    // Ein anderes Programm will dauerhaft den Ton: Spiel anhalten.
                    DiagLog.add("Audiofokus-Änderung: $change")
                    if (change == AudioManager.AUDIOFOCUS_LOSS) handler.post { openMenu() }
                }
                .build()
            val granted = am.requestAudioFocus(request)
            DiagLog.add("Audiofokus: " + when (granted) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "erhalten"
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "verzögert"
                else -> "abgelehnt"
            })
            audioRequest = request
        } catch (t: Throwable) {
            DiagLog.add("Audiofokus: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- Controller erkennen

    private fun isGamepad(d: InputDevice): Boolean =
        !d.isVirtual && ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)

    private fun hasGamepad(): Boolean =
        InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.let { isGamepad(it) } == true }

    private fun updateOverlayVisibility() {
        // Es gibt nur noch den kleinen Menü-Knopf; er bleibt immer sichtbar.
        overlay.visibility = View.VISIBLE
    }

    private fun registerInputListener() {
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = onDevicesChanged(true, deviceId)
            override fun onInputDeviceRemoved(deviceId: Int) = onDevicesChanged(false, deviceId)
            override fun onInputDeviceChanged(deviceId: Int) {}
        }
        inputListener = listener
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(listener, handler)
    }

    private fun onDevicesChanged(added: Boolean, deviceId: Int) {
        updateOverlayVisibility()
        val pad = if (added) InputDevice.getDevice(deviceId)?.takeIf { isGamepad(it) } else null
        showNotice(
            when {
                pad != null -> "Controller verbunden (Spieler ${portOf(pad) + 1})"
                added -> "Eingabegerät verbunden"
                else -> "Eingabegerät getrennt"
            },
            2000,
        )
        if (!added) releaseAllInputs()
    }

    private fun portOf(device: InputDevice?): Int {
        val n = device?.controllerNumber ?: 0
        return if (n > 0) (n - 1).coerceAtMost(3) else 0
    }

    // ---------------------------------------------------------------- Eingabe an das Spiel

    private fun sendRetroKey(port: Int, retroKey: Int, down: Boolean) {
        val id = (port shl 16) or retroKey
        val changed = if (down) heldRetro.add(id) else heldRetro.remove(id)
        if (!changed) return
        retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, retroKey, port)
    }

    // Bildformat: 0 Original, 1 feste 4:3, 2 gestreckt auf den ganzen Bildschirm. Wirkt sofort, auch aus dem Spielmenü.
    private var aspectListenerSet = false

    private fun applyAspectMode() {
        if (!aspectListenerSet) {
            aspectListenerSet = true
            retroView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (prefs.aspectMode == 2) stretchAspect() }
        }
        when (prefs.aspectMode) {
            1 -> retroView.aspectOverride = 4f / 3f
            2 -> stretchAspect()
            else -> retroView.aspectOverride = 0f
        }
    }

    private fun stretchAspect() {
        val w = retroView.width
        val h = retroView.height
        if (w > 0 && h > 0) retroView.aspectOverride = w.toFloat() / h.toFloat()
    }

    private val aspectNames = arrayOf("Original", "4:3", "Ganzer Bildschirm")

    private fun cycleAspect() {
        prefs.aspectMode = (prefs.aspectMode + 1) % aspectNames.size
        applyAspectMode()
        refreshMenuTexts()
    }

    private var rewindOn = false
    private var rewindHeld = false
    private var playStart = 0L

    private fun setRewindHeld(held: Boolean) {
        if (!rewindOn || held == rewindHeld) return
        rewindHeld = held
        retroView.setRewindHeld(held)
    }

    /** Spielzeit seit dem letzten Aufruf zum Spiel addieren. */
    private fun flushPlayTime() {
        if (playStart == 0L || !::game.isInitialized) return
        val now = android.os.SystemClock.elapsedRealtime()
        prefs.addPlaySeconds(system, game.saveKey, (now - playStart) / 1000)
        playStart = if (menuOpen) 0L else now
    }

    private fun releaseAllInputs() {
        if (!::retroView.isInitialized) return
        setRewindHeld(false)
        for (id in heldRetro.toList()) {
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, id and 0xFFFF, id shr 16)
        }
        heldRetro.clear()
        padDown.clear()
        handler.removeCallbacks(comboRunnable)
        for ((port, p) in pads.withIndex()) {
            if (p.hatX != 0f || p.hatY != 0f) retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f, port)
            if (p.lx != 0f || p.ly != 0f) retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, 0f, 0f, port)
            if (p.rx != 0f || p.ry != 0f) retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, 0f, 0f, port)
            p.hatX = 0f; p.hatY = 0f; p.lx = 0f; p.ly = 0f; p.rx = 0f; p.ry = 0f
            p.axisKeys.reset()
        }
        if (::overlay.isInitialized) overlay.releaseAll()
    }

    // Touch-Tasten (immer Anschluss 0)
    override fun onKey(keyCode: Int, down: Boolean) {
        if (menuOpen) return
        sendRetroKey(0, keyCode, down)
    }

    override fun onMotion(source: Int, x: Float, y: Float) {
        if (menuOpen) return
        retroView.sendMotionEvent(source, x, y, 0)
    }

    override fun onMenu() = openMenu()

    private fun isPadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::retroView.isInitialized) return super.dispatchKeyEvent(event)
        val fromPad = PadInput.isPadKey(event)
        PadInput.logFirst(event)
        val down = event.action == KeyEvent.ACTION_DOWN

        if (menuOpen) return handleMenuKey(event, fromPad)

        if (!fromPad) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) openMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Der "Zurück"/"Ansicht"-Knopf mancher Controller kommt als BACK an: er zählt als Select.
        var code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) code = KeyEvent.KEYCODE_BUTTON_SELECT
        if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (down && event.repeatCount == 0) openMenu()
            return true
        }
        if (event.repeatCount > 0) return true

        trackCombo(code, down)
        if (code == KeyEvent.KEYCODE_BUTTON_L2 && rewindOn) {
            setRewindHeld(down)
            return true
        }
        val retro = mapping.retroKey(code) ?: return super.dispatchKeyEvent(event)
        sendRetroKey(portOf(event.device), retro, down)
        return true
    }

    /** Select und Start gleichzeitig lange gedrückt: Menü öffnen. */
    private fun trackCombo(code: Int, down: Boolean) {
        if (code != KeyEvent.KEYCODE_BUTTON_SELECT && code != KeyEvent.KEYCODE_BUTTON_START) return
        if (down) padDown.add(code) else padDown.remove(code)
        val both = KeyEvent.KEYCODE_BUTTON_SELECT in padDown && KeyEvent.KEYCODE_BUTTON_START in padDown
        handler.removeCallbacks(comboRunnable)
        if (both) handler.postDelayed(comboRunnable, 800)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (::retroView.isInitialized && isPadSource(ev.source) && ev.action == MotionEvent.ACTION_MOVE) {
            if (menuOpen) handleMenuMotion(ev) else handlePadMotion(ev)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun handlePadMotion(ev: MotionEvent) {
        val port = portOf(ev.device)
        val p = pads[port]
        val dev = ev.device

        val hx = AxisKeys.hat(ev.getAxisValue(MotionEvent.AXIS_HAT_X))
        val hy = AxisKeys.hat(ev.getAxisValue(MotionEvent.AXIS_HAT_Y))
        val (lx, ly) = AxisKeys.deadzone(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y))
        val rightAxes = if (dev?.getMotionRange(MotionEvent.AXIS_Z) != null) AxisKeys.AXIS_RIGHT_PRIMARY else AxisKeys.AXIS_RIGHT_ALTERNATE
        val (rx, ry) = AxisKeys.deadzone(ev.getAxisValue(rightAxes[0]), ev.getAxisValue(rightAxes[1]))

        if (system == RetroSystem.N64) {
            if (hx != p.hatX || hy != p.hatY) {
                p.hatX = hx; p.hatY = hy
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, hx, hy, port)
            }
            if (lx != p.lx || ly != p.ly) {
                p.lx = lx; p.ly = ly
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, lx, ly, port)
            }
            if (rx != p.rx || ry != p.ry) {
                p.rx = rx; p.ry = ry
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, rx, ry, port)
            }
        } else {
            // SNES und Mega Drive kennen keinen Stick: linker Stick und Steuerkreuz werden zusammengelegt.
            val sx = if (lx > 0.5f) 1f else if (lx < -0.5f) -1f else 0f
            val sy = if (ly > 0.5f) 1f else if (ly < -0.5f) -1f else 0f
            val dx = if (hx != 0f) hx else sx
            val dy = if (hy != 0f) hy else sy
            if (dx != p.hatX || dy != p.hatY) {
                p.hatX = dx; p.hatY = dy
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, dx, dy, port)
            }
        }

        // Analoge Trigger wie Tasten behandeln (L2/R2 laufen durch die Tastenbelegung).
        val lt = maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
        for (edge in p.axisKeys.updateTriggers(lt, rt)) {
            if (edge.keyCode == KeyEvent.KEYCODE_BUTTON_L2 && rewindOn) {
                setRewindHeld(edge.down)
                continue
            }
            val retro = mapping.retroKey(edge.keyCode) ?: continue
            sendRetroKey(port, retro, edge.down)
        }
    }

    // ---------------------------------------------------------------- Menü mit Controller bedienen

    private fun handleMenuKey(event: KeyEvent, fromPad: Boolean): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> moveFocus(View.FOCUS_UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(View.FOCUS_DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(View.FOCUS_LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(View.FOCUS_RIGHT)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                (currentFocus ?: firstMenuButton).performClick()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_START ->
                if (event.repeatCount == 0 && (fromPad || code == KeyEvent.KEYCODE_BACK)) closeMenu()
        }
        return true
    }

    private fun moveFocus(direction: Int) {
        val current = currentFocus
        if (current == null || !current.isShown) {
            firstMenuButton.requestFocus()
            return
        }
        current.focusSearch(direction)?.let { if (it.isShown && it.isEnabled) it.requestFocus() }
    }

    private fun handleMenuMotion(ev: MotionEvent) {
        val hy = AxisKeys.hat(ev.getAxisValue(MotionEvent.AXIS_HAT_Y))
        val sy = ev.getAxisValue(MotionEvent.AXIS_Y)
        val dir = if (hy != 0f) hy else if (sy > 0.6f) 1f else if (sy < -0.6f) -1f else 0f
        if (dir != lastMenuHatY) {
            lastMenuHatY = dir
            if (dir > 0f) moveFocus(View.FOCUS_DOWN) else if (dir < 0f) moveFocus(View.FOCUS_UP)
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_TITLE = "title"
    }
}
