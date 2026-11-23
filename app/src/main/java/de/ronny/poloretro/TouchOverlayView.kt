package de.ronny.poloretro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.swordfish.libretrodroid.GLRetroView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Tasten auf dem Bildschirm für das Radio (Mehrfingerbedienung).
 * Gezeichnet wird in Einheiten der Bildschirmhöhe, damit es auf 1280x720 und anderen Größen passt.
 * Die Tasten senden Retropad-Tastencodes (siehe ControllerMapping) direkt an das Spiel.
 */
class TouchOverlayView(context: Context) : View(context) {

    interface Listener {
        fun onKey(keyCode: Int, down: Boolean)
        fun onMotion(source: Int, x: Float, y: Float)
        fun onMenu()
    }

    enum class Layout { SNES, MEGADRIVE3, MEGADRIVE6, N64 }

    var listener: Listener? = null

    var layoutType: Layout = Layout.SNES
        set(value) {
            field = value
            rebuild()
        }

    /** 0..1 */
    /** Nur der Menü-Knopf, keine Spieltasten (Standard: Spielen per Touch ist am Radio unpraktisch). */
    var menuOnly: Boolean = true
        set(v) { field = v; rebuild() }

    var opacity: Float = 0.55f
        set(value) { field = value.coerceIn(0.1f, 1f); invalidate() }

    /** 0.7..1.4 */
    var sizeFactor: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 1.6f)
            rebuild()
        }

    // ---------------------------------------------------------------- Elemente

    private sealed class Elem

    private class Btn(
        val keyCode: Int,
        val label: String,
        val cx: Float,
        val cy: Float,
        val r: Float,
        val shape: Shape = Shape.CIRCLE,
        val width: Float = 0f,
        val height: Float = 0f,
    ) : Elem()

    private enum class Shape { CIRCLE, PILL }

    /** Kleine Taste, die den rechten Analogstick bewegt (N64 C-Tasten). */
    private class CBtn(val label: String, val dx: Float, val dy: Float, val cx: Float, val cy: Float, val r: Float) : Elem()

    private class Dpad(val cx: Float, val cy: Float, val r: Float) : Elem()

    private class Stick(val cx: Float, val cy: Float, val r: Float) : Elem()

    private class MenuBtn(val rect: RectF) : Elem()

    private var elements: List<Elem> = emptyList()

    // Zustand
    private val pressedKeys = HashSet<Int>()
    private val pressedC = HashSet<CBtn>()
    private var dpadX = 0
    private var dpadY = 0
    private var stickX = 0f
    private var stickY = 0f
    private val captured = HashMap<Int, Elem>()
    private var stickKnobX = 0f
    private var stickKnobY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val rect = RectF()

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        releaseAll()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            elements = emptyList()
            return
        }
        val u = h * sizeFactor
        val list = ArrayList<Elem>()
        val b = KeyEvent.KEYCODE_BUTTON_B

        fun face(key: Int, label: String, cx: Float, cy: Float, r: Float) {
            list.add(Btn(key, label, cx, cy, r))
        }

        // Kleiner Knopf in der oberen rechten Ecke, damit er im Spielbild möglichst wenig stört.
        val menuW = 0.135f * h
        val menuH = 0.066f * h
        val margin = 0.010f * h
        list.add(MenuBtn(RectF(w - margin - menuW, margin, w - margin, margin + menuH)))
        if (menuOnly) {
            elements = list
            invalidate()
            return
        }

        val startW = 0.22f * u
        val startH = 0.085f * u
        fun pill(key: Int, label: String, cx: Float, cy: Float) {
            list.add(Btn(key, label, cx, cy, 0f, Shape.PILL, startW, startH))
        }
        val shoulderW = 0.34f * u
        val shoulderH = 0.11f * u
        fun shoulder(key: Int, label: String, left: Float, top: Float) {
            list.add(Btn(key, label, left + shoulderW / 2, top + shoulderH / 2, 0f, Shape.PILL, shoulderW, shoulderH))
        }

        when (layoutType) {
            Layout.SNES -> {
                list.add(Dpad(0.30f * u, h - 0.30f * u, 0.21f * u))
                val cx = w - 0.28f * u
                val cy = h - 0.30f * u
                val d = 0.135f * u
                val r = 0.09f * u
                face(KeyEvent.KEYCODE_BUTTON_X, "X", cx, cy - d, r)
                face(KeyEvent.KEYCODE_BUTTON_Y, "Y", cx - d, cy, r)
                face(KeyEvent.KEYCODE_BUTTON_A, "A", cx + d, cy, r)
                face(b, "B", cx, cy + d, r)
                shoulder(KeyEvent.KEYCODE_BUTTON_L1, "L", 0.03f * w, 0.03f * h)
                shoulder(KeyEvent.KEYCODE_BUTTON_R1, "R", w - 0.03f * w - shoulderW, 0.03f * h)
                pill(KeyEvent.KEYCODE_BUTTON_SELECT, "Select", w / 2 - 0.14f * u, h - 0.07f * u)
                pill(KeyEvent.KEYCODE_BUTTON_START, "Start", w / 2 + 0.14f * u, h - 0.07f * u)
            }
            Layout.MEGADRIVE3, Layout.MEGADRIVE6 -> {
                list.add(Dpad(0.30f * u, h - 0.30f * u, 0.21f * u))
                val cx = w - 0.30f * u
                val cy = h - 0.28f * u
                val r = 0.095f * u
                val step = 0.19f * u
                // Reihe A B C leicht ansteigend, wie beim echten Controller.
                face(KeyEvent.KEYCODE_BUTTON_Y, "A", cx - step, cy + 0.05f * u, r)
                face(b, "B", cx, cy, r)
                face(KeyEvent.KEYCODE_BUTTON_A, "C", cx + step, cy - 0.05f * u, r)
                if (layoutType == Layout.MEGADRIVE6) {
                    val r2 = 0.075f * u
                    face(KeyEvent.KEYCODE_BUTTON_L1, "X", cx - step, cy - 0.17f * u, r2)
                    face(KeyEvent.KEYCODE_BUTTON_X, "Y", cx, cy - 0.22f * u, r2)
                    face(KeyEvent.KEYCODE_BUTTON_R1, "Z", cx + step, cy - 0.27f * u, r2)
                    pill(KeyEvent.KEYCODE_BUTTON_SELECT, "Mode", w / 2 - 0.14f * u, h - 0.07f * u)
                }
                pill(KeyEvent.KEYCODE_BUTTON_START, "Start", w / 2 + (if (layoutType == Layout.MEGADRIVE6) 0.14f else 0f) * u, h - 0.07f * u)
            }
            Layout.N64 -> {
                // Analogstick unten links, kleines Steuerkreuz oben links.
                list.add(Stick(0.27f * u, h - 0.27f * u, 0.19f * u))
                list.add(Dpad(0.17f * u, 0.37f * u, 0.12f * u))
                // C-Tasten (rechter Analogstick) rechts in der Mitte.
                val ccx = w - 0.22f * u
                val ccy = 0.45f * u
                val cd = 0.10f * u
                val cr = 0.065f * u
                list.add(CBtn("C▲", 0f, -1f, ccx, ccy - cd, cr))
                list.add(CBtn("C▼", 0f, 1f, ccx, ccy + cd, cr))
                list.add(CBtn("C◀", -1f, 0f, ccx - cd, ccy, cr))
                list.add(CBtn("C▶", 1f, 0f, ccx + cd, ccy, cr))
                // A und B unten rechts.
                face(b, "A", w - 0.16f * u, h - 0.20f * u, 0.095f * u)
                face(KeyEvent.KEYCODE_BUTTON_Y, "B", w - 0.36f * u, h - 0.13f * u, 0.085f * u)
                shoulder(KeyEvent.KEYCODE_BUTTON_L1, "L", 0.03f * w, 0.03f * h)
                shoulder(KeyEvent.KEYCODE_BUTTON_R1, "R", w - 0.03f * w - shoulderW, 0.03f * h)
                shoulder(KeyEvent.KEYCODE_BUTTON_L2, "Z", 0.03f * w, 0.03f * h + shoulderH + 0.02f * h)
                pill(KeyEvent.KEYCODE_BUTTON_START, "Start", w / 2, h - 0.07f * u)
            }
        }
        elements = list
        invalidate()
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val a = (opacity * 255).toInt().coerceIn(20, 255)
        for (e in elements) {
            when (e) {
                is Btn -> drawBtn(canvas, e, a)
                is CBtn -> drawC(canvas, e, a)
                is Dpad -> drawDpad(canvas, e, a)
                is Stick -> drawStick(canvas, e, a)
                is MenuBtn -> drawMenu(canvas, e, a)
            }
        }
    }

    private fun base(pressed: Boolean, a: Int) {
        fill.color = if (pressed) 0xFFF2A33A.toInt() else 0xFF2B343E.toInt()
        fill.alpha = if (pressed) min(255, a + 60) else a
        line.color = 0xFFEEF1F4.toInt()
        line.alpha = (a * 0.8f).toInt()
        line.strokeWidth = 0.006f * height
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, size: Float, a: Int, dark: Boolean) {
        text.textSize = size
        text.color = if (dark) 0xFF12161B.toInt() else 0xFFEEF1F4.toInt()
        text.alpha = min(255, a + 40)
        canvas.drawText(label, cx, cy + size * 0.35f, text)
    }

    private fun drawBtn(canvas: Canvas, b: Btn, a: Int) {
        val pressed = b.keyCode in pressedKeys
        base(pressed, a)
        if (b.shape == Shape.CIRCLE) {
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            canvas.drawCircle(b.cx, b.cy, b.r, line)
            drawLabel(canvas, b.label, b.cx, b.cy, b.r * 0.95f, a, pressed)
        } else {
            rect.set(b.cx - b.width / 2, b.cy - b.height / 2, b.cx + b.width / 2, b.cy + b.height / 2)
            canvas.drawRoundRect(rect, b.height / 2, b.height / 2, fill)
            canvas.drawRoundRect(rect, b.height / 2, b.height / 2, line)
            drawLabel(canvas, b.label, b.cx, b.cy, b.height * 0.52f, a, pressed)
        }
    }

    private fun drawC(canvas: Canvas, c: CBtn, a: Int) {
        val pressed = c in pressedC
        base(pressed, a)
        fill.color = if (pressed) 0xFFF2A33A.toInt() else 0xFF6B5B1E.toInt()
        fill.alpha = a
        canvas.drawCircle(c.cx, c.cy, c.r, fill)
        canvas.drawCircle(c.cx, c.cy, c.r, line)
        drawLabel(canvas, c.label, c.cx, c.cy, c.r * 0.9f, a, pressed)
    }

    private fun drawDpad(canvas: Canvas, d: Dpad, a: Int) {
        base(false, a)
        val arm = d.r * 0.36f
        rect.set(d.cx - d.r, d.cy - arm, d.cx + d.r, d.cy + arm)
        canvas.drawRoundRect(rect, arm * 0.4f, arm * 0.4f, fill)
        rect.set(d.cx - arm, d.cy - d.r, d.cx + arm, d.cy + d.r)
        canvas.drawRoundRect(rect, arm * 0.4f, arm * 0.4f, fill)
        // Gedrückte Richtung hervorheben.
        fill.color = 0xFFF2A33A.toInt()
        fill.alpha = min(255, a + 60)
        val seg = d.r * 0.55f
        if (dpadX < 0) canvas.drawCircle(d.cx - d.r * 0.68f, d.cy, seg * 0.5f, fill)
        if (dpadX > 0) canvas.drawCircle(d.cx + d.r * 0.68f, d.cy, seg * 0.5f, fill)
        if (dpadY < 0) canvas.drawCircle(d.cx, d.cy - d.r * 0.68f, seg * 0.5f, fill)
        if (dpadY > 0) canvas.drawCircle(d.cx, d.cy + d.r * 0.68f, seg * 0.5f, fill)
    }

    private fun drawStick(canvas: Canvas, s: Stick, a: Int) {
        base(false, a)
        canvas.drawCircle(s.cx, s.cy, s.r, fill)
        canvas.drawCircle(s.cx, s.cy, s.r, line)
        fill.color = 0xFFEEF1F4.toInt()
        fill.alpha = min(255, a + 40)
        canvas.drawCircle(s.cx + stickKnobX * s.r * 0.6f, s.cy + stickKnobY * s.r * 0.6f, s.r * 0.42f, fill)
    }

    private fun drawMenu(canvas: Canvas, m: MenuBtn, a: Int) {
        base(false, a)
        canvas.drawRoundRect(m.rect, m.rect.height() / 2, m.rect.height() / 2, fill)
        canvas.drawRoundRect(m.rect, m.rect.height() / 2, m.rect.height() / 2, line)
        drawLabel(canvas, "Menü", m.rect.centerX(), m.rect.centerY(), m.rect.height() * 0.5f, a, false)
    }

    // ---------------------------------------------------------------- Berühren

    private fun hit(x: Float, y: Float): Elem? {
        var best: Elem? = null
        var bestDist = Float.MAX_VALUE
        for (e in elements) {
            val dist = when (e) {
                is Btn -> if (e.shape == Shape.CIRCLE) {
                    val d = hypot(x - e.cx, y - e.cy)
                    if (d <= e.r * 1.25f) d / e.r else -1f
                } else {
                    val pad = e.height * 0.25f
                    if (abs(x - e.cx) <= e.width / 2 + pad && abs(y - e.cy) <= e.height / 2 + pad) 0.5f else -1f
                }
                is CBtn -> {
                    val d = hypot(x - e.cx, y - e.cy)
                    if (d <= e.r * 1.3f) d / e.r else -1f
                }
                is Dpad -> {
                    val d = hypot(x - e.cx, y - e.cy)
                    if (d <= e.r * 1.35f) 1.5f else -1f
                }
                is Stick -> {
                    val d = hypot(x - e.cx, y - e.cy)
                    if (d <= e.r * 1.4f) 1.5f else -1f
                }
                is MenuBtn -> if (RectF(e.rect).apply { inset(-e.rect.height() * 0.6f, -e.rect.height() * 0.6f) }.contains(x, y)) 0.5f else -1f
            }
            if (dist >= 0f && dist < bestDist) {
                bestDist = dist
                best = e
            }
        }
        return best
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val e = hit(ev.getX(i), ev.getY(i))
                if (e is MenuBtn) {
                    listener?.onMenu()
                    return true
                }
                recompute(ev, skipIndex = -1)
            }
            MotionEvent.ACTION_MOVE -> recompute(ev, skipIndex = -1)
            MotionEvent.ACTION_POINTER_UP -> {
                captured.remove(ev.getPointerId(ev.actionIndex))
                recompute(ev, skipIndex = ev.actionIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                captured.clear()
                releaseAll()
            }
        }
        invalidate()
        return true
    }

    /** Berechnet aus allen aufliegenden Fingern, welche Tasten gerade gedrückt sind, und meldet nur Änderungen. */
    private fun recompute(ev: MotionEvent, skipIndex: Int) {
        val newKeys = HashSet<Int>()
        val newC = HashSet<CBtn>()
        var nx = 0
        var ny = 0
        var sx = 0f
        var sy = 0f
        val activeIds = HashSet<Int>()
        for (i in 0 until ev.pointerCount) {
            if (i == skipIndex) continue
            val id = ev.getPointerId(i)
            activeIds.add(id)
            val x = ev.getX(i)
            val y = ev.getY(i)
            var e = captured[id]
            if (e == null) {
                e = hit(x, y)
                if (e is Dpad || e is Stick) captured[id] = e
            }
            when (e) {
                is Btn -> newKeys.add(e.keyCode)
                is CBtn -> newC.add(e)
                is Dpad -> {
                    val dead = e.r * 0.28f
                    val dx = x - e.cx
                    val dy = y - e.cy
                    if (dx > dead) nx = 1 else if (dx < -dead) nx = -1
                    if (dy > dead) ny = 1 else if (dy < -dead) ny = -1
                }
                is Stick -> {
                    var dx = (x - e.cx) / e.r
                    var dy = (y - e.cy) / e.r
                    val len = hypot(dx, dy)
                    if (len > 1f) { dx /= len; dy /= len }
                    sx = dx
                    sy = dy
                }
                else -> {}
            }
        }
        captured.keys.retainAll(activeIds)

        // Tasten: Änderungen melden.
        for (k in pressedKeys.toList()) if (k !in newKeys) {
            pressedKeys.remove(k)
            listener?.onKey(k, false)
        }
        for (k in newKeys) if (pressedKeys.add(k)) listener?.onKey(k, true)

        // C-Tasten -> rechter Analogstick.
        val changedC = newC != pressedC
        if (changedC) {
            pressedC.clear()
            pressedC.addAll(newC)
            var cx = 0f
            var cy = 0f
            for (c in pressedC) { cx += c.dx; cy += c.dy }
            listener?.onMotion(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, cx.coerceIn(-1f, 1f), cy.coerceIn(-1f, 1f))
        }

        if (nx != dpadX || ny != dpadY) {
            dpadX = nx
            dpadY = ny
            listener?.onMotion(GLRetroView.MOTION_SOURCE_DPAD, nx.toFloat(), ny.toFloat())
        }
        if (sx != stickX || sy != stickY) {
            stickX = sx
            stickY = sy
            stickKnobX = sx
            stickKnobY = sy
            listener?.onMotion(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, sx, sy)
        }
    }

    /** Alles loslassen (auch beim Ausblenden oder Layoutwechsel), damit keine Taste hängen bleibt. */
    fun releaseAll() {
        for (k in pressedKeys.toList()) listener?.onKey(k, false)
        pressedKeys.clear()
        if (pressedC.isNotEmpty()) {
            pressedC.clear()
            listener?.onMotion(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, 0f, 0f)
        }
        if (dpadX != 0 || dpadY != 0) {
            dpadX = 0
            dpadY = 0
            listener?.onMotion(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f)
        }
        if (stickX != 0f || stickY != 0f || stickKnobX != 0f || stickKnobY != 0f) {
            stickX = 0f
            stickY = 0f
            stickKnobX = 0f
            stickKnobY = 0f
            listener?.onMotion(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, 0f, 0f)
        }
        captured.clear()
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) releaseAll()
    }
}
