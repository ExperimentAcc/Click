package zx.offical.click.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import zx.offical.click.R
import zx.offical.click.util.Ui

/**
 * Horizontal swipe / tap PHOTO–VIDEO mode selector.
 *
 * The indicator follows the finger during a drag and snaps to the nearest
 * half with a smooth decelerate animation on release. A quick tap selects
 * the half under the finger.
 */
class ModeScroller(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    /** Fired with `true` when VIDEO is selected, `false` for PHOTO. */
    var onSelect: ((video: Boolean) -> Unit)? = null

    private val photoTv: TextView
    private val videoTv: TextView
    private val indicator: View

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var indicatorWidth = 0
    private var dragging = false
    private var downX = 0f
    private var startTx = 0f

    init {
        View.inflate(context, R.layout.view_mode_scroller, this)
        indicator = findViewById(R.id.indicator)
        photoTv = findViewById(R.id.mode_photo)
        videoTv = findViewById(R.id.mode_video)
        isClickable = true
        isFocusable = false
        setVideo(false, animate = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = Ui.dp(context, 3)
        indicatorWidth = (w - pad * 2) / 2
        val lp = indicator.layoutParams as LayoutParams
        lp.width = indicatorWidth
        lp.leftMargin = pad
        lp.gravity = android.view.Gravity.NO_GRAVITY
        indicator.layoutParams = lp
        requestLayout()
    }

    /** Move the indicator to PHOTO (false) or VIDEO (true). */
    fun setVideo(video: Boolean, animate: Boolean) {
        val target = if (video) indicatorWidth.toFloat() else 0f
        if (animate && indicatorWidth > 0) {
            indicator.animate()
                .translationX(target)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            indicator.translationX = target
        }
        photoTv.setTextColor(
            if (video) 0xFF8E8E93.toInt() else 0xFFFFD60A.toInt()
        )
        videoTv.setTextColor(
            if (video) 0xFFFFD60A.toInt() else 0xFF8E8E93.toInt()
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downX = event.x
                startTx = indicator.translationX
                handler.removeCallbacksAndMessages(null)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.abs(event.x - downX) > slop) {
                    dragging = true
                }
                if (dragging && indicatorWidth > 0) {
                    indicator.translationX =
                        (startTx + (event.x - downX)).coerceIn(0f, indicatorWidth.toFloat())
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val video =
                        (indicator.translationX + indicatorWidth / 2f) > width / 2f
                    setVideo(video, animate = true)
                    onSelect?.invoke(video)
                } else if (indicatorWidth > 0) {
                    // Quick tap: select the half under the finger.
                    val video = event.x > width / 2f
                    setVideo(video, animate = true)
                    onSelect?.invoke(video)
                }
            }
        }
        return true
    }
}
