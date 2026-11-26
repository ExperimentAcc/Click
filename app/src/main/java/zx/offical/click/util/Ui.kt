package zx.offical.click.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared sizing, colour, haptic and "glass" drawing helpers. */
object Ui {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val RED = 0xFFFF3B30.toInt()
    const val DIM = 0xFFAEAEB2.toInt()

    const val GLASS = 0x73262628
    const val GLASS_STRONG = 0xE61C1C1E.toInt()
    const val GLASS_EDGE = 0x33FFFFFF

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    /**
     * Frosted-glass look: translucent fill, hairline highlight and touch ripple.
     * No live backdrop blur, which would cost a GPU pass on every camera frame.
     */
    fun glass(context: Context, radiusDp: Int, fill: Int = GLASS): Drawable {
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 0.7f), GLASS_EDGE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
    }

    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun confirm(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    /** Includes milliseconds so rapid shots never share a file name. */
    fun mediaTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
