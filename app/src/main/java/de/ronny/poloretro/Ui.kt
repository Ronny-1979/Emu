package de.ronny.poloretro

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Kleine Bausteine für die Oberfläche (alles im Code, ohne Layout-XML). */
object Ui {
    const val BG = 0xFF12161B.toInt()
    const val SURFACE = 0xFF1C232B.toInt()
    const val SURFACE_HI = 0xFF2A3541.toInt()
    const val ACCENT = 0xFFF2A33A.toInt()
    const val TEXT = 0xFFEEF1F4.toInt()
    const val MUTED = 0xFF9AA7B4.toInt()
    const val DANGER = 0xFFE5604D.toInt()

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private fun rounded(color: Int, radius: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    /** Hintergrund mit sichtbarem Fokusrahmen, damit man mit dem Controller sieht, wo man ist. */
    fun focusBackground(context: Context, fill: Int, radiusDp: Int = 12): StateListDrawable {
        val r = dp(context, radiusDp)
        val stroke = dp(context, 3)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(SURFACE_HI, r, ACCENT, stroke))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(SURFACE_HI, r, ACCENT, stroke))
            addState(intArrayOf(), rounded(fill, r))
        }
    }

    fun label(context: Context, text: String, sizeSp: Float = 16f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(
        context: Context,
        text: String,
        fill: Int = SURFACE,
        textColor: Int = TEXT,
        sizeSp: Float = 16f,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(textColor)
        background = focusBackground(context, fill)
        minHeight = dp(context, 52)
        minimumHeight = dp(context, 52)
        setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6))
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun weight(width: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 1f) =
        LinearLayout.LayoutParams(width, height, weight)

    fun vertical(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun spacer(context: Context, widthDp: Int, heightDp: Int = 1): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), dp(context, heightDp))
    }

    fun dialog(context: Context): AlertDialog.Builder =
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
}
