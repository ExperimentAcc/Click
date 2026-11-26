package zx.offical.click

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import zx.offical.click.util.Ui

/**
 * Click's own tiny video player. Used when no other app on the phone can open
 * a video, so recorded clips always play. No extra libraries needed.
 */
class PlayerActivity : Activity() {

    private lateinit var video: VideoView
    private var position = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        position = savedInstanceState?.getInt(KEY_POSITION) ?: 0

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BLACK) }

        video = VideoView(this)
        root.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        video.setMediaController(MediaController(this).also { it.setAnchorView(video) })
        video.setOnPreparedListener {
            if (position > 0) video.seekTo(position)
            video.start()
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Can't play this video", Toast.LENGTH_SHORT).show()
            finish()
            true
        }

        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Ui.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Close"
            background = Ui.glass(this@PlayerActivity, 22, Ui.GLASS_STRONG)
            setOnClickListener { finish() }
        }
        val size = Ui.dp(this, 44)
        root.addView(
            close,
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                topMargin = Ui.dp(this@PlayerActivity, 12)
                marginStart = Ui.dp(this@PlayerActivity, 12)
            }
        )

        setContentView(root)
        video.setVideoURI(uri)
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized) {
            position = video.currentPosition
            video.pause()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_POSITION, position)
    }

    private companion object {
        const val KEY_POSITION = "position"
    }
}
