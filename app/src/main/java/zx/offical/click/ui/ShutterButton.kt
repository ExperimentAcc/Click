package zx.offical.click.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import zx.offical.click.util.Ui

/** Canvas-drawn shutter: white for photo, red for video, red square while recording. */
class ShutterButton(context: Context) : View(context) {

    enum class State { PHOTO, VIDEO, RECORDING }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 3.5f).toFloat()
        color = Ui.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.WHITE }
    private val rect = RectF()

    private var state = State.PHOTO
    /** 0 = full circle, 1 = recording square. */
    private var morph = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Shutter"
    }

    fun setState(newState: State) {
        if (newState == state) return
        state = newState
        fill.color = if (newState == State.PHOTO) Ui.WHITE else Ui.RED
        animator?.cancel()
        animator = ValueAnimator.ofFloat(morph, if (newState == State.RECORDING) 1f else 0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                morph = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f - ring.strokeWidth
        canvas.drawCircle(cx, cy, outer, ring)

        val full = outer - Ui.dp(context, 5)
        val press = if (isPressed) 0.9f else 1f
        val half = (full + (full * 0.46f - full) * morph) * press
        val corner = half + (Ui.dp(context, 7) - half) * morph
        rect.set(cx - half, cy - half, cx + half, cy + half)
        fill.alpha = if (isEnabled) Color.alpha(Ui.WHITE) else 110
        canvas.drawRoundRect(rect, corner, corner, fill)
    }
}
