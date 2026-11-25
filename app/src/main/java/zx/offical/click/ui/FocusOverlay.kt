package zx.offical.click.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import zx.offical.click.R
import zx.offical.click.util.Ui

/**
 * Tap-to-focus overlay.
 *
 * - Tapping anywhere on the viewfinder shows a yellow iOS-style focus square
 *   with a vertical sun-icon exposure compensation slider.
 * - Dragging the sun adjusts the exposure compensation index
 *   (CameraControl.setExposureCompensationIndex()).
 * - The whole overlay fades out smoothly after focusing.
 * - Pinch-to-zoom (ScaleGestureDetector) is hosted here so it works across the
 *   whole viewfinder area in both photo and video modes.
 */
class FocusOverlay(context: Context) : FrameLayout(context) {

    /** Exposure compensation range reported by the camera (may be empty if unsupported). */
    var exposureRange: IntRange = 0..0
        set(value) {
            field = value
            if (visible) layoutSlider()
        }

    /** Fired with the new exposure compensation index while dragging the sun. */
    var onExposureChanged: ((Int) -> Unit)? = null

    /** Optional pinch-to-zoom detector (provided by the host Activity). */
    var scaleGesture: ScaleGestureDetector? = null

    private val square: ImageView
    private val track: View
    private val sun: ImageView

    private val squareSize: Int
    private val trackHeight: Int
    private val trackWidth: Int
    private val sunSize: Int

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private var visible = false
    private var dragging = false
    private var trackTop = 0f
    private var lastX = 0f
    private var lastY = 0f

    init {
        squareSize = Ui.dp(context, 74)
        trackHeight = Ui.dp(context, 136)
        trackWidth = Ui.dp(context, 44)
        sunSize = Ui.dp(context, 38)

        square = ImageView(context).apply {
            setImageResource(R.drawable.ic_focus_square)
            scaleType = ImageView.ScaleType.CENTER
            alpha = 0f
            visibility = INVISIBLE
        }

        track = View(context).apply {
            setBackgroundColor(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
            alpha = 0f
            visibility = INVISIBLE
        }

        sun = ImageView(context).apply {
            setImageResource(R.drawable.ic_sun)
            scaleType = ImageView.ScaleType.CENTER
            alpha = 0f
            visibility = INVISIBLE
        }

        addView(square, LayoutParams(squareSize, squareSize, Gravity.CENTER))
        addView(track, LayoutParams(Ui.dp(context, 3), trackHeight, Gravity.CENTER))
        addView(sun, LayoutParams(sunSize, sunSize, Gravity.CENTER))

        isClickable = true
        isFocusable = false
    }

    /** Show the focus square + exposure slider at the given view coordinates. */
    fun showAt(x: Float, y: Float) {
        lastX = x
        lastY = y
        layoutSlider()
        visible = true
        visibility = VISIBLE

        square.scaleX = 1.5f
        square.scaleY = 1.5f
        square.alpha = 0f
        square.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(150)
            .start()

        track.alpha = 0f
        track.animate().alpha(1f).setDuration(150).start()
        sun.alpha = 0f
        sun.animate().alpha(1f).setDuration(150).start()

        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 3000)
    }

    private fun layoutSlider() {
        // Focus square centered on the tap point.
        val sqLp = square.layoutParams as LayoutParams
        sqLp.gravity = Gravity.NO_GRAVITY
        sqLp.leftMargin = (lastX - squareSize / 2f).toInt()
        sqLp.topMargin = (lastY - squareSize / 2f).toInt()
        square.layoutParams = sqLp

        // Exposure track centered horizontally, directly below the square.
        val trackLeft = (width - trackWidth) / 2f
        trackTop = lastY + squareSize / 2f + Ui.dp(context, 18)

        val tLp = track.layoutParams as LayoutParams
        tLp.gravity = Gravity.NO_GRAVITY
        tLp.leftMargin = trackLeft.toInt()
        tLp.topMargin = trackTop.toInt()
        track.layoutParams = tLp

        // Sun starts at the middle of the track.
        val sunLeft = trackLeft + (trackWidth - sunSize) / 2f
        val sunCenterY = trackTop + trackHeight / 2f
        val sLp = sun.layoutParams as LayoutParams
        sLp.gravity = Gravity.NO_GRAVITY
        sLp.leftMargin = sunLeft.toInt()
        sLp.topMargin = (sunCenterY - sunSize / 2f).toInt()
        sun.layoutParams = sLp
        sun.translationX = 0f
        sun.translationY = 0f

        if (exposureRange.isEmpty()) {
            track.alpha = 0f
            sun.alpha = 0f
            track.visibility = INVISIBLE
            sun.visibility = INVISIBLE
        } else {
            track.visibility = VISIBLE
            sun.visibility = VISIBLE
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (visible) layoutSlider()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let pinch-to-zoom take priority when a two-finger gesture is detected.
        scaleGesture?.onTouchEvent(event)?.let { consumed ->
            if (consumed) return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!visible) {
                    showAt(event.x, event.y)
                } else {
                    dragging = hitTrack(event.x, event.y)
                    if (dragging) updateSun(event.y)
                }
                handler.removeCallbacks(hideRunnable)
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) updateSun(event.y)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                handler.removeCallbacks(hideRunnable)
                handler.postDelayed(hideRunnable, 2600)
            }
        }
        return true
    }

    private fun hitTrack(x: Float, y: Float): Boolean {
        val left = (width - trackWidth) / 2f
        return x in left..(left + trackWidth) && y in trackTop..(trackTop + trackHeight)
    }

    private fun updateSun(y: Float) {
        val sunCenter = y.coerceIn(trackTop, trackTop + trackHeight)
        val baseCenter = trackTop + trackHeight / 2f
        sun.translationY = sunCenter - baseCenter

        if (exposureRange.first == exposureRange.last || trackHeight <= 0) return
        val index = exposureRange.first +
            ((sunCenter - trackTop) / trackHeight * (exposureRange.last - exposureRange.first))
                .toInt()
                .coerceIn(exposureRange)
        onExposureChanged?.invoke(index)
    }

    private fun hide() {
        if (!visible) return
        visible = false
        handler.removeCallbacks(hideRunnable)

        val animate = { v: View ->
            v.animate()
                .alpha(0f)
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(170)
                .withEndAction { v.visibility = INVISIBLE }
                .start()
        }
        animate(square)
        track.animate().alpha(0f).setDuration(150).withEndAction {
            track.visibility = INVISIBLE
        }.start()
        sun.animate().alpha(0f).setDuration(150).withEndAction {
            sun.visibility = INVISIBLE
        }.start()
    }
}
