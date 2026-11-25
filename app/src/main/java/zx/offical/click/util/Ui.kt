package zx.offical.click.util

import android.content.Context
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small view/haptic/format helpers shared across the app. */
object Ui {

    /** Convert a dp value to pixels. */
    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    /** Light, crisp haptic tick — used for mode switches. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Soft confirmation haptic — used for shutter taps. */
    fun confirm(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRMATION)
    }

    /** MediaStore-safe timestamp, e.g. 20261125_143022. */
    fun mediaTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
