package zx.offical.click.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import zx.offical.click.util.Ui

/** One titled group of mutually exclusive choices. */
class Setting(
    val title: String,
    val options: List<String>,
    val selected: Int,
    val onPick: (Int) -> Unit
)

/** Glass bottom sheet holding every secondary control. */
class SettingsSheet(context: Context) : FrameLayout(context) {

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val side = Ui.dp(context, 18)
        setPadding(side, Ui.dp(context, 8), side, Ui.dp(context, 18))
    }
    private val card = ScrollView(context).apply {
        background = Ui.glass(context, 30, Ui.GLASS_STRONG)
        clipToOutline = true
        isVerticalScrollBarEnabled = false
        isClickable = true
        addView(list)
    }

    val isOpen get() = visibility == VISIBLE

    init {
        setBackgroundColor(0x66000000)
        visibility = GONE
        setOnClickListener { close() }
        val margin = Ui.dp(context, 10)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(margin, Ui.dp(context, 80), margin, margin) })
    }

    fun setBottomInset(inset: Int) {
        setPadding(0, 0, 0, inset)
    }

    fun show(settings: List<Setting>) {
        val opening = !isOpen
        val scroll = card.scrollY
        list.removeAllViews()
        settings.forEach { add(it) }
        visibility = VISIBLE
        if (opening) {
            card.translationY = Ui.dp(context, 60).toFloat()
            card.alpha = 0f
            card.animate().translationY(0f).alpha(1f).setDuration(180).start()
        } else {
            card.post { card.scrollTo(0, scroll) }
        }
    }

    fun close() {
        visibility = GONE
    }

    private fun add(setting: Setting) {
        val title = TextView(context).apply {
            text = setting.title
            textSize = 12f
            setTextColor(Ui.DIM)
            setPadding(Ui.dp(context, 4), Ui.dp(context, 12), 0, Ui.dp(context, 7))
        }
        val row = LinearLayout(context)
        setting.options.forEachIndexed { index, label ->
            val selected = index == setting.selected
            val chip = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (selected) Ui.BLACK else Ui.WHITE)
                background = Ui.glass(context, 20, if (selected) Ui.YELLOW else Ui.GLASS)
                minWidth = Ui.dp(context, 64)
                setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0)
                isSelected = selected
                setOnClickListener { view: View ->
                    Ui.tick(view)
                    setting.onPick(index)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(context, 40)
            ).apply { marginEnd = Ui.dp(context, 8) })
        }
        list.addView(title)
        list.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }
}
