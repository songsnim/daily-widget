package dev.daily.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView

// Shared look for the sheet and settings screens (same palette as the widget).
object Ui {
    const val BASE = 0xFF17171C.toInt()
    const val SHEET = 0xFF202027.toInt()
    const val SURFACE = 0xFF2C2C35.toInt()
    const val BLUE = 0xFF3182F6.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUB = 0xFF9E9EA4.toInt()

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun Context.round(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun Context.topRound(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        val r = dp(radius).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    fun Context.text(s: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun Context.button(label: String, color: Int, textColor: Int = TEXT, onClick: () -> Unit) =
        text(label, 16f, textColor, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(56)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(color, 16), null)
            setOnClickListener { onClick() }
        }
}
