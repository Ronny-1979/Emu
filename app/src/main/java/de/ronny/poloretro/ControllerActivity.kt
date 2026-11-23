package de.ronny.poloretro

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Steuerung je Konsole festlegen: Funktion antippen (oder mit dem Controller auswählen),
 * dann die gewünschte Taste am Bluetooth-Controller drücken. Rechts läuft ein Live-Test.
 */
class ControllerActivity : Activity() {
    private lateinit var prefs: Prefs
    private var system = RetroSystem.SNES
    private var mapping = ControllerMapping.default()
    private var listening: SystemButtons.Entry? = null

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopListening("Zeit abgelaufen, nichts geändert.") }

    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var cancelButton: Button
    private val tabButtons = ArrayList<Button>()

    private val axisKeys = AxisKeys()
    private var deviceName = "—"
    private var lastKey = "—"
    private var lastPhysical: Int? = null
    private var lastAxes = "—"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        system = RetroSystem.fromId(intent.getStringExtra(EXTRA_SYSTEM)) ?: RetroSystem.SNES
        mapping = prefs.mappingFor(system)
        setContentView(buildUi())
        rebuildList()
        renderLive()
    }

    private fun buildUi(): View {
        val pad = Ui.dp(this, 16)
        val root = Ui.vertical(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(pad, Ui.dp(this@ControllerActivity, 10), pad, pad)
        }

        val header = Ui.horizontal(this)
        header.addView(Ui.label(this, "Steuerung", 28f, Ui.ACCENT, true), Ui.weight())
        header.addView(Ui.button(this, "Standard wiederherstellen", onClick = { confirmReset() }))
        header.addView(Ui.spacer(this, 8))
        header.addView(Ui.button(this, "Fertig", onClick = { finish() }))
        root.addView(header)

        val tabs = Ui.horizontal(this)
        for ((i, s) in RetroSystem.values().withIndex()) {
            val b = Ui.button(this, s.shortLabel, sizeSp = 14f, onClick = { selectSystem(s) })
            tabButtons.add(b)
            val lp = Ui.weight()
            lp.topMargin = Ui.dp(this, 10)
            if (i > 0) lp.leftMargin = Ui.dp(this, 6)
            tabs.addView(b, lp)
        }
        root.addView(tabs)

        val body = Ui.horizontal(this).apply { gravity = Gravity.TOP }
        val leftScroll = ScrollView(this).apply { isFillViewport = true }
        listBox = Ui.vertical(this)
        leftScroll.addView(listBox)
        val leftLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        leftLp.topMargin = Ui.dp(this, 10)
        body.addView(leftScroll, leftLp)

        val right = Ui.vertical(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.SURFACE)
                cornerRadius = Ui.dp(this@ControllerActivity, 14).toFloat()
            }
            setPadding(Ui.dp(this@ControllerActivity, 18), Ui.dp(this@ControllerActivity, 14), Ui.dp(this@ControllerActivity, 18), Ui.dp(this@ControllerActivity, 14))
        }
        status = Ui.label(this, "", 17f, Ui.ACCENT, true)
        right.addView(status)
        cancelButton = Ui.button(this, "Abbrechen", onClick = { stopListening("Abgebrochen.") })
        cancelButton.visibility = View.GONE
        right.addView(cancelButton)
        right.addView(Ui.label(this, "Live-Test", 15f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@ControllerActivity, 16), 0, Ui.dp(this@ControllerActivity, 4))
        })
        live = Ui.label(this, "", 16f, Ui.TEXT)
        right.addView(live)
        right.addView(Ui.label(this, hintText(), 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@ControllerActivity, 16), 0, 0)
        })
        val rightLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        rightLp.topMargin = Ui.dp(this, 10)
        rightLp.leftMargin = Ui.dp(this, 14)
        body.addView(right, rightLp)

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        updateTabs()
        return root
    }

    private fun hintText(): String =
        "So geht's: Funktion links antippen oder mit dem Steuerkreuz anwählen und A drücken, dann die neue Taste am Controller drücken. " +
            "Mit Steuerkreuz und A/B kannst du diese Seite komplett ohne Touch bedienen. " +
            "Die Belegung gilt nur für die gewählte Konsole. Analogsticks sind fest (N64: linker Stick = Analogstick, rechter Stick = C-Tasten)."

    private fun updateTabs() {
        for ((i, b) in tabButtons.withIndex()) {
            val active = RetroSystem.values()[i] == system
            b.background = Ui.focusBackground(this, if (active) Ui.ACCENT else Ui.SURFACE)
            b.setTextColor(if (active) 0xFF12161B.toInt() else Ui.TEXT)
        }
    }

    private fun selectSystem(s: RetroSystem) {
        stopListening(null)
        system = s
        mapping = prefs.mappingFor(system)
        updateTabs()
        rebuildList()
        renderLive()
    }

    private fun rebuildList() {
        listBox.removeAllViews()
        for (entry in SystemButtons.forSystem(system, prefs.genesis6)) {
            val phys = mapping.physicalFor(entry.retroKey)
            val text = "${entry.label}     ←     " + (if (phys == null) "nicht belegt" else SystemButtons.physicalName(phys))
            val b = Ui.button(this, text, sizeSp = 18f, onClick = { startListening(entry) })
            b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = Ui.dp(this, 8)
            listBox.addView(b, lp)
        }
        if (system == RetroSystem.N64) {
            listBox.addView(Ui.label(this, "C-Tasten = rechter Stick, Z = Taste oben (siehe Liste)", 13f, Ui.MUTED).apply {
                setPadding(0, Ui.dp(this@ControllerActivity, 10), 0, 0)
            })
        }
        listBox.getChildAt(0)?.requestFocus()
    }

    private fun startListening(entry: SystemButtons.Entry) {
        listening = entry
        status.text = "Drücke jetzt am Controller die Taste für \"${entry.label}\" ..."
        cancelButton.visibility = View.VISIBLE
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 10_000)
    }

    private fun stopListening(message: String?) {
        listening = null
        handler.removeCallbacks(timeout)
        cancelButton.visibility = View.GONE
        status.text = message ?: ""
    }

    private fun bind(physical: Int) {
        val entry = listening ?: return
        mapping = mapping.withBinding(entry.retroKey, physical)
        prefs.setMapping(system, mapping)
        DiagLog.add("Steuerung ${system.label}: ${entry.label} <- ${SystemButtons.physicalName(physical)}")
        stopListening("\"${entry.label}\" = ${SystemButtons.physicalName(physical)}")
        rebuildList()
        renderLive()
    }

    private fun confirmReset() {
        Ui.dialog(this)
            .setMessage("Die Steuerung für ${system.label} auf Standard zurücksetzen?")
            .setPositiveButton("Zurücksetzen") { _, _ ->
                prefs.resetMapping(system)
                mapping = prefs.mappingFor(system)
                stopListening("Standard wiederhergestellt.")
                rebuildList()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------------------------------------------------------------- Eingaben

    private fun isPad(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        PadInput.logFirst(event)
        if (!PadInput.isPadKey(event)) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        // Der "Zurück"/"Ansicht"-Knopf mancher Controller kommt als BACK an und zählt wie im Spiel als Select.
        val code = if (event.keyCode == KeyEvent.KEYCODE_BACK) KeyEvent.KEYCODE_BUTTON_SELECT else event.keyCode
        deviceName = event.device?.name ?: "—"

        if (down && event.repeatCount == 0) {
            lastKey = SystemButtons.physicalName(code)
            lastPhysical = code
            renderLive()
        }

        if (listening != null) {
            if (down && event.repeatCount == 0 && code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) bind(code)
            return true
        }

        // Ohne Touch bedienen: A = anklicken, B = zurück.
        if (down && event.repeatCount == 0) {
            when (code) {
                KeyEvent.KEYCODE_BUTTON_A -> { (currentFocus ?: listBox.getChildAt(0))?.performClick(); return true }
                KeyEvent.KEYCODE_BUTTON_B -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!isPad(ev.source) || ev.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(ev)
        deviceName = ev.device?.name ?: "—"
        val dev = ev.device
        val right = if (dev?.getMotionRange(MotionEvent.AXIS_Z) != null) AxisKeys.AXIS_RIGHT_PRIMARY else AxisKeys.AXIS_RIGHT_ALTERNATE
        val lt = maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
        lastAxes = String.format(
            Locale.GERMANY,
            "Linker Stick: %.2f / %.2f\nRechter Stick: %.2f / %.2f\nTrigger L / R: %.2f / %.2f\nKreuz: %.0f / %.0f",
            ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y),
            ev.getAxisValue(right[0]), ev.getAxisValue(right[1]),
            lt, rt,
            ev.getAxisValue(MotionEvent.AXIS_HAT_X), ev.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        for (edge in axisKeys.updateTriggers(lt, rt)) {
            if (!edge.down) continue
            lastKey = SystemButtons.physicalName(edge.keyCode)
            lastPhysical = edge.keyCode
            if (listening != null) bind(edge.keyCode)
        }
        renderLive()
        return true
    }

    private fun renderLive() {
        val phys = lastPhysical
        val function = if (phys == null) "" else {
            val retro = mapping.retroKey(phys)
            val label = SystemButtons.forSystem(system, prefs.genesis6).firstOrNull { it.retroKey == retro }?.label
            "\n→ " + when {
                label != null -> "${system.shortLabel}: $label"
                retro != null && retro in ControllerMapping.DPAD_KEYS -> "Steuerkreuz"
                else -> "keine Funktion bei ${system.shortLabel}"
            }
        }
        live.text = "Controller: $deviceName\nLetzte Taste: $lastKey$function\n\n$lastAxes"
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SYSTEM = "system"
    }
}
