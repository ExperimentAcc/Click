package zx.offical.click.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import zx.offical.click.util.Ui

/**
 * Sits exactly over the viewfinder: rule-of-thirds grid, tap to focus,
 * vertical drag for exposure, pinch to zoom and sideways swipe to change mode.
 */
class FocusOverlay(context: Context) : View(context) {

    var onFocus: ((x: Float, y: Float) -> Unit)? = null
    var onExposure: ((index: Int) -> Unit)? = null
    var onPinch: ((factor: Float) -> Unit)? = null
    var onSwipe: ((toVideo: Boolean) -> Unit)? = null

    var showGrid = false
        set(value) {
            field = value
            invalidate()
        }

    private var exposureRange = 0..0
    private var exposureIndex = 0

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.YELLOW }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var focusVisible = false
    private var focusX = 0f
    private var focusY = 0f
    private val hideFocus = Runnable {
        focusVisible = false
        invalidate()
    }

    private enum class Gesture { NONE, EXPOSURE, SWIPE, PINCH }

    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var startIndex = 0

    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onPinch?.invoke(detector.scaleFactor)
                return true
            }
        }
    )

    init {
        isClickable = true
    }

    /** Call whenever the camera is rebound. */
    fun reset(range: IntRange) {
        exposureRange = range
        exposureIndex = 0.coerceIn(range.first, range.last)
        removeCallbacks(hideFocus)
        hideFocus.run()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.NONE
                downX = event.x
                downY = event.y
                startIndex = exposureIndex
            }

            MotionEvent.ACTION_POINTER_DOWN -> gesture = Gesture.PINCH

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (gesture == Gesture.NONE && hypot(dx, dy) > slop) {
                    gesture = if (abs(dy) > abs(dx)) Gesture.EXPOSURE else Gesture.SWIPE
                }
                if (gesture == Gesture.EXPOSURE && focusVisible && exposureRange.last > exposureRange.first) {
                    val span = exposureRange.last - exposureRange.first
                    val index = (startIndex - dy / Ui.dp(context, 220) * span)
                        .roundToInt()
                        .coerceIn(exposureRange.first, exposureRange.last)
                    if (index != exposureIndex) {
                        exposureIndex = index
                        onExposure?.invoke(index)
                    }
                    removeCallbacks(hideFocus)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.NONE) {
                    performClick()
                    showFocus(event.x, event.y)
                } else if (gesture == Gesture.SWIPE && abs(event.x - downX) > Ui.dp(context, 70)) {
                    onSwipe?.invoke(event.x > downX)
                } else if (focusVisible) {
                    scheduleHide()
                }
            }

            MotionEvent.ACTION_CANCEL -> if (focusVisible) scheduleHide()
        }
        return true
    }

    private fun showFocus(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusVisible = true
        // A new focus point starts again from neutral exposure.
        if (exposureIndex != 0 && 0 in exposureRange) {
            exposureIndex = 0
            onExposure?.invoke(0)
        }
        onFocus?.invoke(x, y)
        scheduleHide()
        invalidate()
    }

    private fun scheduleHide() {
        removeCallbacks(hideFocus)
        postDelayed(hideFocus, 3500)
    }

    override fun onDraw(canvas: Canvas) {
        if (showGrid) {
            line.color = 0x66FFFFFF
            line.strokeWidth = Ui.dp(context, 0.7f).toFloat()
            for (i in 1..2) {
                val x = width * i / 3f
                val y = height * i / 3f
                canvas.drawLine(x, 0f, x, height.toFloat(), line)
                canvas.drawLine(0f, y, width.toFloat(), y, line)
            }
        }
        if (!focusVisible) return

        val half = Ui.dp(context, 36).toFloat()
        line.color = Ui.YELLOW
        line.strokeWidth = Ui.dp(context, 1.5f).toFloat()
        canvas.drawRect(focusX - half, focusY - half, focusX + half, focusY + half, line)

        if (exposureRange.last <= exposureRange.first) return
        // Slider sits beside the square, flipping sides near the right edge.
        val gap = half + Ui.dp(context, 20)
        val x = if (focusX + gap + half < width) focusX + gap else focusX - gap
        val track = Ui.dp(context, 120).toFloat()
        val top = focusY - track / 2
        val fraction = (exposureIndex - exposureRange.first).toFloat() /
            (exposureRange.last - exposureRange.first)
        val sunY = top + track * (1 - fraction)
        val sun = Ui.dp(context, 6).toFloat()
        canvas.drawLine(x, top, x, sunY - sun * 2, line)
        canvas.drawLine(x, sunY + sun * 2, x, top + track, line)
        canvas.drawCircle(x, sunY, sun, dot)
        for (i in 0 until 8) {
            val angle = Math.PI * i / 4
            val c = Math.cos(angle).toFloat()
            val s = Math.sin(angle).toFloat()
            canvas.drawLine(
                x + c * sun * 1.4f, sunY + s * sun * 1.4f,
                x + c * sun * 1.9f, sunY + s * sun * 1.9f, line
            )
        }
    }
}
