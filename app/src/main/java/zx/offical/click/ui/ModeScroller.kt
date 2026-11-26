package zx.offical.click.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import zx.offical.click.util.Ui

/** Glass VIDEO | PHOTO capsule with a highlight that follows taps and drags. */
class ModeScroller(context: Context) : View(context) {

    var onSelect: ((video: Boolean) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = Ui.dp(context, 13).toFloat()
        letterSpacing = 0.06f
    }
    private val rect = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var video = false
    /** Highlight position: 0 = VIDEO, 1 = PHOTO. */
    private var position = 1f
    private var animator: ValueAnimator? = null
    private var downX = 0f
    private var startPosition = 0f
    private var dragging = false

    init {
        isClickable = true
        contentDescription = "Photo or video mode"
    }

    fun setVideo(isVideo: Boolean) {
        video = isVideo
        animator?.cancel()
        animator = ValueAnimator.ofFloat(position, if (isVideo) 0f else 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = Ui.dp(context, 4).toFloat()
        val segment = (w - pad * 2) / 2

        paint.style = Paint.Style.FILL
        paint.color = Ui.GLASS
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        paint.color = 0x33FFFFFF
        rect.set(pad + segment * position, pad, pad + segment * (position + 1), h - pad)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        val baseline = h / 2 - (text.ascent() + text.descent()) / 2
        text.color = if (position < 0.5f) Ui.YELLOW else Ui.WHITE
        canvas.drawText("VIDEO", pad + segment / 2, baseline, text)
        text.color = if (position >= 0.5f) Ui.YELLOW else Ui.WHITE
        canvas.drawText("PHOTO", pad + segment * 1.5f, baseline, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                downX = event.x
                startPosition = position
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > slop) dragging = true
                if (dragging) {
                    position = (startPosition + (event.x - downX) / (width / 2f)).coerceIn(0f, 1f)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val target = if (dragging) position < 0.5f else event.x < width / 2f
                performClick()
                choose(target)
            }

            MotionEvent.ACTION_CANCEL -> setVideo(video)
        }
        return true
    }

    private fun choose(target: Boolean) {
        val changed = target != video
        setVideo(target)
        if (changed) onSelect?.invoke(target)
    }
}
