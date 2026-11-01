package dev.carfiles

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Единый тёмный стиль приложения без внешних UI-библиотек. */
object Theme {
    val bg = Color.parseColor("#0B1220")
    val surface = Color.parseColor("#131C2E")
    val surfaceHi = Color.parseColor("#1B2740")
    val stroke = Color.parseColor("#26324A")
    val text = Color.parseColor("#EAF0FA")
    val muted = Color.parseColor("#8EA0BC")
    val accent = Color.parseColor("#4C8DFF")
    val ok = Color.parseColor("#2DD4A7")
    val warn = Color.parseColor("#F5B84C")
    val danger = Color.parseColor("#FF6B6B")
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

private fun Context.ripple(content: GradientDrawable) =
    RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), content, null)

fun Context.screen(root: LinearLayout.() -> Unit): ScrollView {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(32))
        root()
    }
    return ScrollView(this).apply {
        setBackgroundColor(Theme.bg); fitsSystemWindows = true; isVerticalScrollBarEnabled = false
        addView(column, ViewGroup.LayoutParams(-1, -2))
    }
}

fun Context.label(text: CharSequence, sizeSp: Float = 15f, color: Int = Theme.text, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text; textSize = sizeSp; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

fun LinearLayout.gap(context: Context, dpValue: Int) {
    addView(View(context), LinearLayout.LayoutParams(1, context.dp(dpValue)))
}

fun LinearLayout.header(title: String, subtitle: String? = null) {
    addView(context.label(title, 30f, bold = true))
    if (subtitle != null) addView(context.label(subtitle, 15f, Theme.muted).apply { setPadding(0, context.dp(6), 0, 0) })
    gap(context, 20)
}

fun LinearLayout.card(accentStroke: Int? = null, body: LinearLayout.() -> Unit): LinearLayout {
    val c = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.rounded(Theme.surface, 20, accentStroke ?: Theme.stroke)
        setPadding(context.dp(18), context.dp(16), context.dp(18), context.dp(16))
        body()
    }
    addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(12) })
    return c
}

fun LinearLayout.chip(text: String, color: Int): TextView {
    val t = context.label(text, 13f, color, bold = true).apply {
        background = context.rounded(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color)), 50)
        setPadding(context.dp(12), context.dp(5), context.dp(12), context.dp(5))
    }
    addView(t, LinearLayout.LayoutParams(-2, -2))
    return t
}

fun LinearLayout.row(name: String, value: String, valueColor: Int = Theme.text) {
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, context.dp(6), 0, context.dp(6))
        addView(context.label(name, 14f, Theme.muted), LinearLayout.LayoutParams(0, -2, 1f))
        addView(context.label(value, 14f, valueColor, bold = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1.4f))
    })
}

fun LinearLayout.primaryButton(text: String, color: Int = Theme.accent, onClick: () -> Unit): TextView {
    val b = context.label(text, 17f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER; minHeight = context.dp(56)
        background = context.ripple(context.rounded(color, 18))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }
    addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10) })
    return b
}

fun LinearLayout.secondaryButton(text: String, onClick: () -> Unit): TextView {
    val b = context.label(text, 15f, Theme.text, bold = true).apply {
        gravity = Gravity.CENTER; minHeight = context.dp(48)
        background = context.ripple(context.rounded(Theme.surfaceHi, 16, Theme.stroke))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }
    addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10) })
    return b
}

/** Большая плитка действия: значок, заголовок, подпись. */
fun LinearLayout.actionCard(glyph: String, title: String, subtitle: String, tint: Int, onClick: () -> Unit) {
    val c = context
    val tile = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = c.ripple(c.rounded(Theme.surface, 20, Theme.stroke))
        setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        addView(c.label(glyph, 24f).apply {
            gravity = Gravity.CENTER
            background = c.rounded(Color.argb(40, Color.red(tint), Color.green(tint), Color.blue(tint)), 14)
        }, LinearLayout.LayoutParams(c.dp(52), c.dp(52)))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(c.dp(14), 0, 0, 0)
            addView(c.label(title, 17f, bold = true))
            addView(c.label(subtitle, 13f, Theme.muted).apply { setPadding(0, c.dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(c.label("›", 26f, Theme.muted))
    }
    addView(tile, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.dp(12) })
}
