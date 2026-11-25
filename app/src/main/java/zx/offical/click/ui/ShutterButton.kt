package zx.offical.click.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import zx.offical.click.R

/**
 * Classic iOS-style shutter button.
 *
 * - PHOTO:        white ring + white inner dot.
 * - VIDEO idle:   white ring + red inner circle.
 * - RECORDING:    red rounded-square background + white rounded-square outline
 *                 (the red circle morphs into the recording shape).
 */
class ShutterButton(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    enum class State { PHOTO, VIDEO, RECORDING }

    private val ring: View
    private val iconA: ImageView
    private val iconB: ImageView

    private var state = State.PHOTO
    private var showingA = true

    init {
        View.inflate(context, R.layout.view_shutter, this)
        ring = findViewById(R.id.shutter_ring)
        iconA = findViewById(R.id.shutter_icon_a)
        iconB = findViewById(R.id.shutter_icon_b)
        isClickable = true
        isFocusable = true
    }

    fun setState(newState: State, animate: Boolean) {
        if (newState == state) return
        state = newState

        ring.setBackgroundResource(
            if (newState == State.RECORDING) R.drawable.bg_recording else R.drawable.shutter_ring
        )

        val iconRes = when (newState) {
            State.PHOTO -> R.drawable.ic_shutter_photo
            State.VIDEO -> R.drawable.ic_shutter_video
            State.RECORDING -> R.drawable.ic_shutter_rec
        }

        val showIn = if (showingA) iconB else iconA
        val hideIn = if (showingA) iconA else iconB
        showIn.setImageResource(iconRes)

        if (!animate) {
            showIn.alpha = 1f
            hideIn.alpha = 0f
            return
        }

        hideIn.animate().alpha(0f).setDuration(110).start()
        showIn.alpha = 0f
        showIn.animate()
            .alpha(1f)
            .setDuration(110)
            .withEndAction { showingA = !showingA }
            .start()
    }

    fun isRecording(): Boolean = state == State.RECORDING
}
