package de.ronny.poloretro

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Erkennt Controller-Tasten. Manche Controller und Funk-Sticks melden sich je nach Betriebsart nicht als
 * "Gamepad", sondern als Tastatur. Ihre Tasten (Kreuz, A/B/X/Y, Start, Select, Schultertasten) sind
 * trotzdem Controller-Tasten und werden dann auch im Spiel angenommen.
 */
object PadInput {
    fun isPadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    /** Tastencodes, die es nur auf Controllern gibt: Kreuz und BUTTON_A bis BUTTON_MODE. */
    fun isPadKeyCode(code: Int): Boolean =
        code in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    fun isPadKey(event: KeyEvent): Boolean {
        if (isPadSource(event.source)) return true
        val dev = event.device ?: return false
        return !dev.isVirtual && isPadKeyCode(event.keyCode)
    }

    private val logged = HashSet<Int>()

    /** Schreibt je Eingabegerät einmal ins Protokoll, wie es sich meldet (hilft bei der Fehlersuche). */
    fun logFirst(event: KeyEvent) {
        val dev = event.device ?: return
        if (!logged.add(dev.id)) return
        DiagLog.add(
            "Eingabegerät: \"${dev.name}\", Quelle 0x${Integer.toHexString(event.source)}, " +
                "Spieler ${dev.controllerNumber}, erste Taste ${KeyEvent.keyCodeToString(event.keyCode)}, " +
                "als Controller erkannt: ${isPadKey(event)}"
        )
    }
}
