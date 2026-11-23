package de.ronny.poloretro

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Zuordnung "Taste am Controller" -> "Retropad-Taste" für eine Konsole.
 *
 * Retropad-Tasten werden hier mit Android-Tastencodes benannt, so wie LibretroDroid sie erwartet
 * (KEYCODE_BUTTON_A = Retropad A, rechts; KEYCODE_BUTTON_B = Retropad B, unten).
 * Physische Controller melden A/B und X/Y vertauscht, deshalb sind sie in den Standardwerten getauscht.
 */
class ControllerMapping private constructor(private val map: Map<Int, Int>) {

    /** Retropad-Taste für eine physische Taste, oder null, wenn die Taste nichts auslöst. */
    fun retroKey(physical: Int): Int? = map[physical]

    fun isMapped(physical: Int): Boolean = map.containsKey(physical)

    /** Physische Taste, die gerade [retro] auslöst (die erste, wenn mehrere). */
    fun physicalFor(retro: Int): Int? = map.entries.firstOrNull { it.value == retro }?.key

    /** Neue Belegung: [physical] löst nun [retro] aus. Alte Belegungen beider Seiten werden entfernt. */
    fun withBinding(retro: Int, physical: Int): ControllerMapping {
        val next = LinkedHashMap<Int, Int>()
        for ((p, r) in map) {
            if (r == retro || p == physical) continue
            next[p] = r
        }
        // D-Pad-Tasten bleiben immer erhalten, sonst wäre der Controller im Menü nicht mehr bedienbar.
        for ((p, r) in DEFAULTS) if (r in DPAD_KEYS && !next.containsKey(p) && p != physical) next[p] = r
        next[physical] = retro
        return ControllerMapping(next)
    }

    fun serialize(): String = map.entries.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        val DEFAULTS: Map<Int, Int> = linkedMapOf(
            KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B, // unten  -> Retropad B
            KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A, // rechts -> Retropad A
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y, // links  -> Retropad Y
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_X, // oben   -> Retropad X
            KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR to KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        fun default(): ControllerMapping = ControllerMapping(LinkedHashMap(DEFAULTS))

        /** Liest eine gespeicherte Belegung. Bei leerem oder kaputtem Text gelten die Standardwerte. */
        fun parse(text: String?): ControllerMapping {
            if (text.isNullOrBlank()) return default()
            val result = LinkedHashMap<Int, Int>()
            for (part in text.split(',')) {
                val pair = part.trim().split(':')
                if (pair.size != 2) return default()
                val p = pair[0].toIntOrNull() ?: return default()
                val r = pair[1].toIntOrNull() ?: return default()
                if (p <= 0 || r <= 0) return default()
                result[p] = r
            }
            if (result.isEmpty()) return default()
            // D-Pad muss immer funktionieren.
            for ((p, r) in DEFAULTS) if (r in DPAD_KEYS && !result.containsKey(p)) result[p] = r
            return ControllerMapping(result)
        }
    }
}

/** Beschriftung der Retropad-Tasten je Konsole (so wie auf dem echten Controller). */
object SystemButtons {
    data class Entry(val retroKey: Int, val label: String)

    fun forSystem(system: RetroSystem, genesis6: Boolean = false): List<Entry> = when (system) {
        RetroSystem.SNES -> listOf(
            Entry(KeyEvent.KEYCODE_BUTTON_A, "A"),
            Entry(KeyEvent.KEYCODE_BUTTON_B, "B"),
            Entry(KeyEvent.KEYCODE_BUTTON_X, "X"),
            Entry(KeyEvent.KEYCODE_BUTTON_Y, "Y"),
            Entry(KeyEvent.KEYCODE_BUTTON_L1, "L"),
            Entry(KeyEvent.KEYCODE_BUTTON_R1, "R"),
            Entry(KeyEvent.KEYCODE_BUTTON_START, "Start"),
            Entry(KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
        )
        RetroSystem.MEGADRIVE -> buildList {
            add(Entry(KeyEvent.KEYCODE_BUTTON_Y, "A"))
            add(Entry(KeyEvent.KEYCODE_BUTTON_B, "B"))
            add(Entry(KeyEvent.KEYCODE_BUTTON_A, "C"))
            if (genesis6) {
                add(Entry(KeyEvent.KEYCODE_BUTTON_L1, "X"))
                add(Entry(KeyEvent.KEYCODE_BUTTON_X, "Y"))
                add(Entry(KeyEvent.KEYCODE_BUTTON_R1, "Z"))
                add(Entry(KeyEvent.KEYCODE_BUTTON_SELECT, "Mode"))
            }
            add(Entry(KeyEvent.KEYCODE_BUTTON_START, "Start"))
        }
        RetroSystem.NES, RetroSystem.GB, RetroSystem.GBC -> listOf(
            Entry(KeyEvent.KEYCODE_BUTTON_A, "A"),
            Entry(KeyEvent.KEYCODE_BUTTON_B, "B"),
            Entry(KeyEvent.KEYCODE_BUTTON_START, "Start"),
            Entry(KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
        )
        RetroSystem.GBA -> listOf(
            Entry(KeyEvent.KEYCODE_BUTTON_A, "A"),
            Entry(KeyEvent.KEYCODE_BUTTON_B, "B"),
            Entry(KeyEvent.KEYCODE_BUTTON_L1, "L"),
            Entry(KeyEvent.KEYCODE_BUTTON_R1, "R"),
            Entry(KeyEvent.KEYCODE_BUTTON_START, "Start"),
            Entry(KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
        )
        RetroSystem.MASTERSYSTEM, RetroSystem.GAMEGEAR -> listOf(
            Entry(KeyEvent.KEYCODE_BUTTON_B, "Taste 1"),
            Entry(KeyEvent.KEYCODE_BUTTON_A, "Taste 2"),
            Entry(KeyEvent.KEYCODE_BUTTON_START, "Start"),
        )
        RetroSystem.N64 -> listOf(
            Entry(KeyEvent.KEYCODE_BUTTON_B, "A"),
            Entry(KeyEvent.KEYCODE_BUTTON_Y, "B"),
            Entry(KeyEvent.KEYCODE_BUTTON_L2, "Z"),
            Entry(KeyEvent.KEYCODE_BUTTON_L1, "L"),
            Entry(KeyEvent.KEYCODE_BUTTON_R1, "R"),
            Entry(KeyEvent.KEYCODE_BUTTON_START, "Start"),
        )
    }

    /** Kurzer Name einer physischen Controller-Taste für die Anzeige (ohne Android-Aufrufe, damit testbar). */
    fun physicalName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "A (unten)"
        KeyEvent.KEYCODE_BUTTON_B -> "B (rechts)"
        KeyEvent.KEYCODE_BUTTON_X -> "X (links)"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y (oben)"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1 / LB"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1 / RB"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2 / LT"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2 / RT"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3 (Stick drücken)"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3 (Stick drücken)"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select / Zurück"
        KeyEvent.KEYCODE_BUTTON_MODE -> "Mode / Home"
        KeyEvent.KEYCODE_BUTTON_C -> "C"
        KeyEvent.KEYCODE_BUTTON_Z -> "Z"
        KeyEvent.KEYCODE_DPAD_UP -> "Kreuz hoch"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Kreuz runter"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Kreuz links"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Kreuz rechts"
        else -> "Taste $keyCode"
    }
}

/**
 * Analoge Trigger und das Steuerkreuz (Hat) als Tasten. Viele Bluetooth-Controller melden L2/R2
 * nur als Achse. Die Bearbeitung ist ohne Android-Objekte testbar.
 */
class AxisKeys {
    private var l2 = false
    private var r2 = false

    class Edge(val keyCode: Int, val down: Boolean)

    /** Liefert die Tastenwechsel, wenn die Trigger-Werte die Schwelle über- oder unterschreiten. */
    fun updateTriggers(left: Float, right: Float): List<Edge> {
        val out = ArrayList<Edge>(2)
        val nl = if (l2) left > RELEASE else left > PRESS
        val nr = if (r2) right > RELEASE else right > PRESS
        if (nl != l2) { l2 = nl; out.add(Edge(KeyEvent.KEYCODE_BUTTON_L2, nl)) }
        if (nr != r2) { r2 = nr; out.add(Edge(KeyEvent.KEYCODE_BUTTON_R2, nr)) }
        return out
    }

    fun reset() { l2 = false; r2 = false }

    companion object {
        const val PRESS = 0.6f
        const val RELEASE = 0.4f

        /** Radiale Totzone: kleine Auslenkungen werden 0, der Rest wird neu auf 0..1 gestreckt. */
        fun deadzone(x: Float, y: Float, zone: Float = 0.15f): Pair<Float, Float> {
            val len = Math.sqrt((x * x + y * y).toDouble()).toFloat()
            if (len < zone) return 0f to 0f
            val scaled = ((len - zone) / (1f - zone)).coerceIn(0f, 1f)
            return (x / len * scaled) to (y / len * scaled)
        }

        /** Hat-Achse (-1, 0, +1) aus einem Gleitkommawert. */
        fun hat(value: Float): Float = when {
            value > 0.5f -> 1f
            value < -0.5f -> -1f
            else -> 0f
        }

        /** Welche Achsen-Gruppe ist der rechte Stick: (Z, RZ) oder (RX, RY)? */
        val AXIS_RIGHT_PRIMARY = intArrayOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        val AXIS_RIGHT_ALTERNATE = intArrayOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
    }
}
