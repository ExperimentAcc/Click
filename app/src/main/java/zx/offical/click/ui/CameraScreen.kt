package zx.offical.click.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import zx.offical.click.R
import zx.offical.click.util.Ui

/** Builds and lays out the whole camera screen. Holds no camera logic. */
class CameraScreen(context: Context) : FrameLayout(context) {

    val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    val overlay = FocusOverlay(context)
    /** Hides the viewfinder while the camera is being reconfigured. */
    val cover = View(context).apply { setBackgroundColor(Ui.BLACK) }
    private val viewfinder = FrameLayout(context)

    val screenFlash = View(context).apply {
        setBackgroundColor(Ui.WHITE)
        visibility = GONE
    }

    val flashButton = iconButton(R.drawable.ic_flash_off, "Flash")
    val settingsButton = iconButton(R.drawable.ic_more, "Camera settings")
    private val topPill = LinearLayout(context).apply {
        background = Ui.glass(context, 24)
        addView(flashButton, LinearLayout.LayoutParams(dp(48), dp(44)))
        addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(44)))
    }

    val videoBadge = label(13f).apply {
        background = Ui.glass(context, 20)
        setPadding(dp(14), 0, dp(14), 0)
        visibility = GONE
    }
    val recordTimer = label(15f).apply {
        background = Ui.glass(context, 10, Ui.RED)
        typeface = Typeface.MONOSPACE
        setPadding(dp(10), dp(4), dp(10), dp(4))
        visibility = GONE
    }
    val countdown = label(96f).apply {
        setShadowLayer(24f, 0f, 0f, Ui.BLACK)
        visibility = GONE
    }

    val zoomBar = ZoomBar(context)
    val shutter = ShutterButton(context)
    val modeScroller = ModeScroller(context)
    val thumbnail = ImageView(context).apply {
        setImageResource(R.drawable.ic_thumb)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.glass(context, 26)
        contentDescription = "Open latest photo or video"
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }
    val flipButton = iconButton(R.drawable.ic_camera_flip, "Switch camera").apply {
        background = Ui.glass(context, 26)
    }

    private val permissionText = label(16f)
    val permissionButton = label(15f).apply {
        text = "Open Settings"
        setTextColor(Ui.BLACK)
        background = Ui.glass(context, 24, Ui.YELLOW)
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    private val permissionPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Ui.BLACK)
        isClickable = true
        setPadding(dp(32), 0, dp(32), 0)
        addView(permissionText)
        addView(permissionButton, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })
        visibility = GONE
    }

    val sheet = SettingsSheet(context)

    /** Icons and labels that turn to stay upright when the phone is rotated. */
    val rotatingViews: List<View> =
        listOf(flashButton, settingsButton, thumbnail, flipButton, countdown)

    private var insetTop = 0
    private var insetBottom = 0
    private var heightOverWidth = 4f / 3f

    init {
        setBackgroundColor(Ui.BLACK)
        viewfinder.addView(previewView, match())
        viewfinder.addView(cover, match())
        viewfinder.addView(overlay, match())
        addView(viewfinder, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        addView(screenFlash, match())

        addView(topPill, anchored(Gravity.TOP or Gravity.END))
        addView(videoBadge, anchored(Gravity.TOP or Gravity.START, height = dp(40)))
        addView(recordTimer, anchored(Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(zoomBar, anchored(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        addView(shutter, anchored(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dp(80), dp(80)))
        addView(thumbnail, anchored(Gravity.BOTTOM or Gravity.START, dp(52), dp(52)))
        addView(modeScroller, anchored(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dp(172), dp(46)))
        addView(flipButton, anchored(Gravity.BOTTOM or Gravity.END, dp(52), dp(52)))
        addView(countdown, anchored(Gravity.CENTER))
        addView(permissionPanel, match())
        addView(sheet, match())
        applyMargins()

        @Suppress("DEPRECATION")
        setOnApplyWindowInsetsListener { _, insets ->
            insetTop = insets.systemWindowInsetTop
            insetBottom = insets.systemWindowInsetBottom
            applyMargins()
            sheet.setBottomInset(insetBottom)
            layoutViewfinder()
            insets
        }
    }

    fun setViewfinderRatio(ratio: Float) {
        heightOverWidth = ratio
        layoutViewfinder()
    }

    fun showPermissionMessage(message: String?) {
        permissionText.text = message ?: ""
        permissionPanel.visibility = if (message == null) GONE else VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Layout params cannot be changed reliably in the middle of a layout pass.
        post { layoutViewfinder() }
    }

    private fun layoutViewfinder() {
        if (width == 0 || height == 0) return
        var w = width
        var h = (w * heightOverWidth).toInt()
        if (h > height) {
            h = height
            w = (h / heightOverWidth).toInt()
        }
        // All ratios share the centre line of the 4:3 frame, then stay on screen.
        val centre = insetTop + dp(68) + (width * 4f / 3f).toInt() / 2
        val top = (centre - h / 2)
            .coerceAtLeast(if (h < width * 1.5f) insetTop + dp(68) else insetTop)
            .coerceAtMost(height - h)
        val params = viewfinder.layoutParams as LayoutParams
        if (params.width == w && params.height == h && params.topMargin == top) return
        params.width = w
        params.height = h
        params.topMargin = top
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        viewfinder.layoutParams = params
    }

    private fun applyMargins() {
        val top = insetTop + dp(10)
        place(topPill, top = top, end = dp(16))
        place(videoBadge, top = top + dp(2), start = dp(16))
        place(recordTimer, top = top + dp(8))
        val row = insetBottom + dp(18)
        place(thumbnail, bottom = row, start = dp(28))
        place(flipButton, bottom = row, end = dp(28))
        place(modeScroller, bottom = row + dp(3))
        place(shutter, bottom = row + dp(74))
        place(zoomBar, bottom = row + dp(170))
    }

    private fun place(view: View, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) {
        val params = view.layoutParams as LayoutParams
        params.topMargin = top
        params.bottomMargin = bottom
        params.marginStart = start
        params.marginEnd = end
        view.layoutParams = params
    }

    private fun dp(value: Int) = Ui.dp(context, value)

    private fun match() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    private fun anchored(
        gravity: Int,
        width: Int = LayoutParams.WRAP_CONTENT,
        height: Int = LayoutParams.WRAP_CONTENT
    ) = LayoutParams(width, height, gravity)

    private fun label(size: Float) = TextView(context).apply {
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Ui.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun iconButton(icon: Int, description: String) = ImageView(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        isClickable = true
        isFocusable = true
    }
}
