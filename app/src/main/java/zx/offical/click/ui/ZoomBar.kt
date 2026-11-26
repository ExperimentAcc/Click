package zx.offical.click.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import zx.offical.click.util.Ui

/** Round zoom stops inside a glass capsule; the active stop shows the live ratio. */
class ZoomBar(context: Context) : LinearLayout(context) {

    var onPick: ((Float) -> Unit)? = null

    private var stops = emptyList<Float>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = Ui.glass(context, 24, 0x4D000000)
        val pad = Ui.dp(context, 4)
        setPadding(pad, pad, pad, pad)
        visibility = INVISIBLE
    }

    fun update(min: Float, max: Float, current: Float) {
        // Only offer stops this lens can actually reach; 0.5x needs an ultra-wide.
        val wanted = buildList {
            if (min < 0.95f) add(min)
            addAll(listOf(1f, 2f, 4f).filter { it >= min - 0.01f && it <= max + 0.01f })
        }
        if (wanted != stops) rebuild(wanted)
        visibility = if (stops.size > 1) VISIBLE else INVISIBLE

        val active = stops.indexOfLast { current >= it - 0.05f }.coerceAtLeast(0)
        for (i in 0 until childCount) {
            val chip = getChildAt(i) as TextView
            val selected = i == active
            val value = if (selected) current else stops[i]
            val label = format(value) + if (selected) "×" else ""
            if (chip.text.toString() != label) chip.text = label
            chip.setTextColor(if (selected) Ui.YELLOW else Ui.WHITE)
            chip.scaleX = if (selected) 1.12f else 0.92f
            chip.scaleY = chip.scaleX
        }
    }

    private fun format(value: Float): String {
        val rounded = Math.round(value * 10) / 10f
        val whole = abs(rounded - rounded.toInt()) < 0.01f
        val text = if (whole) rounded.toInt().toString()
        else String.format(Locale.US, "%.1f", rounded)
        return text.removePrefix("0")
    }

    private fun rebuild(wanted: List<Float>) {
        stops = wanted
        removeAllViews()
        wanted.forEach { stop ->
            val chip = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x66000000)
                }
                contentDescription = "Zoom ${format(stop)} times"
                setOnClickListener {
                    Ui.tick(it)
                    onPick?.invoke(stop)
                }
            }
            val size = Ui.dp(context, 36)
            addView(chip, LayoutParams(size, size).apply {
                marginStart = Ui.dp(context, 3)
                marginEnd = Ui.dp(context, 3)
            })
        }
    }
}
