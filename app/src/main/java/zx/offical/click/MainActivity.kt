package zx.offical.click

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import zx.offical.click.camera.Aspect
import zx.offical.click.camera.CameraConfig
import zx.offical.click.camera.CameraEngine
import zx.offical.click.media.MediaRepo
import zx.offical.click.ui.CameraScreen
import zx.offical.click.ui.Setting
import zx.offical.click.ui.ShutterButton
import zx.offical.click.util.Ui

/**
 * Click: a plain Activity that connects [CameraScreen] to [CameraEngine].
 * There are no services or receivers; CameraX releases the camera on stop.
 */
class MainActivity : Activity(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var screen: CameraScreen
    private lateinit var engine: CameraEngine
    private lateinit var prefs: SharedPreferences
    private lateinit var orientation: OrientationEventListener

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    // Context-dependent values must stay lazy: no Context exists during construction.
    private val ui by lazy { ContextCompat.getMainExecutor(this) }

    // Session state
    private var video = false
    private var front = false
    private var photoFlash = ImageCapture.FLASH_MODE_OFF
    private var torch = false
    private var iso: Int? = null
    private var zoom = 1f
    private var capturing = false
    private var recording: Recording? = null
    private var countdownLeft = 0
    private var lastMedia: Uri? = null
    private var askedMicrophone = false

    // Saved preferences
    private var aspect = Aspect.FOUR_THREE
    private var quality: Quality = Quality.FHD
    private var fps = 30
    private var timerSeconds = 0
    private var grid = false
    private var mirrorSelfie = true
    private var fastShutter = false

    // Set when another app asked us for a photo or video.
    private var imageRequest = false
    private var videoRequest = false

    // ---------------------------------------------------------------- Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        goEdgeToEdge()

        imageRequest = intent?.action == MediaStore.ACTION_IMAGE_CAPTURE
        videoRequest = intent?.action == MediaStore.ACTION_VIDEO_CAPTURE
        video = videoRequest
        prefs = getSharedPreferences("click", MODE_PRIVATE)
        loadPreferences()

        screen = CameraScreen(this)
        setContentView(screen)
        engine = CameraEngine(this, this, screen.previewView) { min, max, current ->
            zoom = current
            screen.zoomBar.update(min, max, current)
        }
        wireUi()
        if (!MediaRepo.hasCamera(this)) {
            screen.showPermissionMessage("Click needs camera access to take photos.")
        }

        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) = onRotated(degrees)
        }

        val missing = MediaRepo.missing(this)
        if (savedInstanceState == null && missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_LAUNCH)
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (orientation.canDetectOrientation()) orientation.enable()
        if (MediaRepo.hasCamera(this)) startCamera()
        refreshThumbnail()
    }

    override fun onPause() {
        orientation.disable()
        cancelCountdown()
        stopRecording()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        torch = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        updateFlashButton()
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // An empty result means the dialog was interrupted, not answered.
        if (permissions.isEmpty()) return
        if (requestCode == REQUEST_LAUNCH) {
            if (MediaRepo.hasCamera(this)) {
                startCamera()
            } else {
                screen.showPermissionMessage("Click needs camera access to take photos.")
            }
        }
        if (requestCode == REQUEST_ALBUM && !MediaRepo.hasAlbumAccess(this)) {
            toast("Allow photo access in Settings to see your album")
        }
        refreshThumbnail()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen.sheet.isOpen) screen.sheet.close() else super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0 && !screen.sheet.isOpen) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // ------------------------------------------------------------------- Wiring

    private fun wireUi() {
        screen.shutter.setOnClickListener { onShutter() }
        screen.flipButton.setOnClickListener { flipCamera() }
        screen.flashButton.setOnClickListener { cycleFlash() }
        screen.settingsButton.setOnClickListener { openSettings() }
        screen.videoBadge.setOnClickListener { openSettings() }
        screen.thumbnail.setOnClickListener { openAlbum() }
        screen.permissionButton.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
        screen.modeScroller.onSelect = { switchMode(it) }
        screen.zoomBar.onPick = { engine.setZoom(it) }
        screen.overlay.onSwipe = { switchMode(it) }
        screen.overlay.onPinch = { engine.zoomBy(it) }
        screen.overlay.onFocus = { x, y -> engine.focusAt(x, y) }
        screen.overlay.onExposure = { engine.setExposure(it) }
        screen.previewView.previewStreamState.observe(
            this,
            Observer<PreviewView.StreamState> {
                if (it == PreviewView.StreamState.STREAMING) revealViewfinder()
            }
        )

        screen.overlay.showGrid = grid
        screen.modeScroller.setVideo(video)
        if (imageRequest || videoRequest) {
            screen.modeScroller.visibility = View.INVISIBLE
            screen.thumbnail.visibility = View.INVISIBLE
        }
        updateModeUi()
    }

    // ------------------------------------------------------------------- Camera

    private fun startCamera() {
        screen.showPermissionMessage(null)
        if (engine.isReady) return
        engine.start(
            onReady = { if (!isDestroyed && !engine.isReady) rebind() },
            onError = { toast("Camera could not be started") }
        )
    }

    private fun currentConfig() =
        CameraConfig(video, front, aspect, quality, fps, mirrorSelfie, fastShutter)

    private fun rebind() {
        if (recording != null || !MediaRepo.hasCamera(this)) return
        screen.cover.animate().cancel()
        screen.cover.alpha = 1f
        updateModeUi()
        val wantedZoom = zoom

        var bound = engine.bind(currentConfig())
        if (!bound && video && (fps != 30 || quality != Quality.FHD)) {
            fps = 30
            quality = Quality.FHD
            bound = engine.bind(currentConfig())
            if (bound) toast("That video setting isn't supported, using 1080p 30")
        }
        if (!bound) {
            toast("Camera unavailable")
            return
        }

        // Fall back to a quality the current lens really offers.
        val qualities = engine.supportedQualities()
        if (video && qualities.isNotEmpty() && quality !in qualities) {
            quality = if (Quality.FHD in qualities) Quality.FHD else qualities.first()
            engine.bind(currentConfig())
        }
        if (video && fps !in engine.supportedFps(front)) fps = 30

        engine.setZoom(wantedZoom)
        engine.setTorch(video && torch)
        engine.imageCapture?.flashMode = hardwareFlashMode()
        if (iso != null) engine.setIso(iso)
        screen.overlay.reset(engine.exposureRange())
        updateModeUi()
        // Safety net in case the stream state never changes.
        handler.postDelayed({ revealViewfinder() }, 900)
    }

    private fun revealViewfinder() {
        screen.cover.animate().alpha(0f).setDuration(180).start()
    }

    private fun updateModeUi() {
        screen.setViewfinderRatio(if (video) Aspect.WIDE.heightOverWidth else aspect.heightOverWidth)
        if (recording == null) {
            screen.shutter.setState(
                if (video) ShutterButton.State.VIDEO else ShutterButton.State.PHOTO
            )
        }
        screen.videoBadge.visibility = if (video && recording == null) View.VISIBLE else View.GONE
        screen.videoBadge.text = "${qualityName(quality)}  ·  $fps"
        updateFlashButton()
    }

    private fun switchMode(toVideo: Boolean) {
        if (toVideo == video || imageRequest || videoRequest) return
        if (recording != null || capturing || countdownLeft > 0) {
            screen.modeScroller.setVideo(video)
            return
        }
        video = toVideo
        zoom = 1f
        torch = false
        screen.modeScroller.setVideo(video)
        Ui.tick(screen.modeScroller)
        rebind()
        if (video && !MediaRepo.hasMicrophone(this) && !askedMicrophone) {
            askedMicrophone = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    private fun flipCamera() {
        if (!engine.isReady || recording != null || capturing || countdownLeft > 0) return
        if (!engine.hasLens(!front)) {
            toast("No other camera found")
            return
        }
        Ui.tick(screen.flipButton)
        screen.flipButton.animate().rotationBy(180f).setDuration(260).start()
        front = !front
        zoom = 1f
        torch = false
        iso = null
        rebind()
    }

    // -------------------------------------------------------------------- Flash

    /** The front camera uses the white screen instead of a hardware flash. */
    private fun hardwareFlashMode() =
        if (front || !engine.hasFlashUnit) ImageCapture.FLASH_MODE_OFF else photoFlash

    private fun cycleFlash() {
        if (!engine.isReady || capturing) return
        val screenFlashOnly = front && !video
        if (!screenFlashOnly && !engine.hasFlashUnit) {
            toast("This camera has no flash")
            return
        }
        Ui.tick(screen.flashButton)
        if (video) {
            torch = !torch
            engine.setTorch(torch)
        } else {
            photoFlash = when (photoFlash) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON ->
                    if (front) ImageCapture.FLASH_MODE_OFF else ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
            engine.imageCapture?.flashMode = hardwareFlashMode()
        }
        updateFlashButton()
    }

    private fun updateFlashButton() {
        val on = if (video) torch else photoFlash == ImageCapture.FLASH_MODE_ON
        val auto = !video && photoFlash == ImageCapture.FLASH_MODE_AUTO
        screen.flashButton.setImageResource(
            when {
                on -> R.drawable.ic_flash_on
                auto -> R.drawable.ic_flash_auto
                else -> R.drawable.ic_flash_off
            }
        )
        screen.flashButton.imageTintList =
            ColorStateList.valueOf(if (on || auto) Ui.YELLOW else Ui.WHITE)
    }

    // ------------------------------------------------------------------ Shutter

    private fun onShutter() {
        if (!engine.isReady) return
        if (recording != null) {
            Ui.confirm(screen.shutter)
            stopRecording()
            return
        }
        if (countdownLeft > 0) {
            cancelCountdown()
            return
        }
        if (capturing) return
        if (!MediaRepo.canSave(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE
            )
            return
        }
        Ui.confirm(screen.shutter)
        if (timerSeconds > 0) {
            countdownLeft = timerSeconds
            countdownTick.run()
        } else {
            capture()
        }
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            if (countdownLeft <= 0) {
                screen.countdown.visibility = View.GONE
                capture()
                return
            }
            screen.countdown.text = countdownLeft.toString()
            screen.countdown.visibility = View.VISIBLE
            countdownLeft--
            handler.postDelayed(this, 1000)
        }
    }

    private fun cancelCountdown() {
        handler.removeCallbacks(countdownTick)
        countdownLeft = 0
        if (::screen.isInitialized) screen.countdown.visibility = View.GONE
    }

    private fun capture() {
        if (video) startRecording() else takePhoto()
    }

    // -------------------------------------------------------------------- Photo

    private fun takePhoto() {
        if (engine.imageCapture == null) return
        capturing = true
        screen.shutter.isEnabled = false
        if (front && photoFlash == ImageCapture.FLASH_MODE_ON) {
            setScreenFlash(true)
            // Give the display and auto exposure a moment to adapt.
            handler.postDelayed({ savePhoto() }, 450)
        } else {
            blink()
            savePhoto()
        }
    }

    private fun savePhoto() {
        val capture = engine.imageCapture
        if (capture == null) {
            finishCapture()
            return
        }
        @Suppress("DEPRECATION")
        val target: Uri? =
            if (imageRequest) intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT) else null

        var stream: OutputStream? = null
        try {
            val builder = if (target != null) {
                val output = contentResolver.openOutputStream(target)
                    ?: throw IllegalStateException("Cannot open $target")
                stream = output
                ImageCapture.OutputFileOptions.Builder(output)
            } else {
                ImageCapture.OutputFileOptions.Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaRepo.photoValues()
                )
            }
            val metadata = ImageCapture.Metadata()
            metadata.isReversedHorizontal = front && mirrorSelfie
            val options = builder.setMetadata(metadata).build()

            capture.takePicture(options, ui, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    stream?.close()
                    finishCapture()
                    onPhotoSaved(result.savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    stream?.close()
                    finishCapture()
                    toast("Couldn't take the photo")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture failed", e)
            stream?.close()
            finishCapture()
            toast("Couldn't take the photo")
        }
    }

    private fun finishCapture() {
        capturing = false
        screen.shutter.isEnabled = true
        setScreenFlash(false)
    }

    private fun onPhotoSaved(uri: Uri?) {
        if (!imageRequest) {
            uri?.let { showThumbnail(it) }
            return
        }
        val result = Intent()
        if (uri != null) {
            // The caller gave no output file, so it expects a small bitmap.
            MediaRepo.thumbnail(this, uri, 320)?.let { result.putExtra("data", it) }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun blink() {
        screen.cover.animate().cancel()
        screen.cover.alpha = 1f
        screen.cover.animate().alpha(0f).setDuration(160).start()
    }

    private fun setScreenFlash(on: Boolean) {
        screen.screenFlash.visibility = if (on) View.VISIBLE else View.GONE
        val params = window.attributes
        params.screenBrightness =
            if (on) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    // -------------------------------------------------------------------- Video

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val recorder = engine.videoCapture?.output ?: return
        @Suppress("DEPRECATION")
        val target: Uri? =
            if (videoRequest) intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT) else null

        var file: ParcelFileDescriptor? = null
        try {
            var pending = if (target != null) {
                val descriptor = contentResolver.openFileDescriptor(target, "rw")
                    ?: throw IllegalStateException("Cannot open $target")
                file = descriptor
                recorder.prepareRecording(
                    this, FileDescriptorOutputOptions.Builder(descriptor).build()
                )
            } else {
                val options = MediaStoreOutputOptions.Builder(
                    contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                )
                    .setContentValues(MediaRepo.videoValues())
                    .build()
                recorder.prepareRecording(this, options)
            }
            if (MediaRepo.hasMicrophone(this)) {
                pending = pending.withAudioEnabled()
            } else {
                toast("Recording without sound: microphone not allowed")
            }

            recording = pending.start(ui) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> screen.recordTimer.text =
                        formatDuration(event.recordingStats.recordedDurationNanos)

                    is VideoRecordEvent.Finalize -> {
                        file?.close()
                        onRecordingFinished(event, target)
                    }
                }
            }
            screen.recordTimer.text = formatDuration(0)
            screen.recordTimer.visibility = View.VISIBLE
            screen.shutter.setState(ShutterButton.State.RECORDING)
            setRecordingControls(true)
        } catch (e: Exception) {
            Log.e(TAG, "Recording could not start", e)
            file?.close()
            toast("Couldn't start recording")
        }
    }

    /** The file is only complete once the Finalize event arrives. */
    private fun stopRecording() {
        val active = recording ?: return
        screen.shutter.isEnabled = false
        active.stop()
    }

    private fun onRecordingFinished(event: VideoRecordEvent.Finalize, target: Uri?) {
        recording = null
        screen.shutter.isEnabled = true
        screen.recordTimer.visibility = View.GONE
        setRecordingControls(false)
        updateModeUi()

        // Leaving the app or filling the disk ends with an "error" but a playable file.
        val usable = !event.hasError() ||
            event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ||
            event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ||
            event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        if (!usable) {
            Log.e(TAG, "Recording failed: ${event.error}", event.cause)
            toast("Recording failed")
            return
        }
        val uri = target ?: event.outputResults.outputUri
        if (videoRequest) {
            val result = Intent().setData(uri)
            // Only our own MediaStore entry may be re-granted to the caller.
            if (target == null) result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setResult(RESULT_OK, result)
            finish()
        } else {
            showThumbnail(uri)
            toast("Video saved")
        }
    }

    private fun setRecordingControls(active: Boolean) {
        val visibility = if (active) View.INVISIBLE else View.VISIBLE
        screen.settingsButton.visibility = visibility
        screen.flipButton.visibility = visibility
        if (!imageRequest && !videoRequest) {
            screen.modeScroller.visibility = visibility
            screen.thumbnail.visibility = visibility
        }
        if (active) screen.videoBadge.visibility = View.GONE
    }

    private fun formatDuration(nanos: Long): String {
        val seconds = nanos / 1_000_000_000
        return String.format(
            Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60
        )
    }

    // -------------------------------------------------------------------- Album

    private fun refreshThumbnail() {
        if (io.isShutdown) return
        io.execute {
            val uri = MediaRepo.latest(contentResolver)
            val bitmap = uri?.let { MediaRepo.thumbnail(this, it, 192) }
            handler.post {
                lastMedia = uri
                if (bitmap != null) {
                    screen.thumbnail.setImageBitmap(bitmap)
                } else {
                    screen.thumbnail.setImageResource(R.drawable.ic_thumb)
                }
            }
        }
    }

    private fun showThumbnail(uri: Uri) {
        lastMedia = uri
        if (io.isShutdown) return
        io.execute {
            val bitmap = MediaRepo.thumbnail(this, uri, 192) ?: return@execute
            handler.post {
                screen.thumbnail.setImageBitmap(bitmap)
                screen.thumbnail.scaleX = 0.7f
                screen.thumbnail.scaleY = 0.7f
                screen.thumbnail.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
        }
    }

    private fun openAlbum() {
        val uri = lastMedia
        if (uri == null) {
            if (MediaRepo.hasAlbumAccess(this)) {
                toast("No photos yet")
            } else {
                val wanted = MediaRepo.missing(this)
                requestPermissions(wanted.toTypedArray(), REQUEST_ALBUM)
            }
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: Exception) {
            Log.w(TAG, "No gallery app", e)
            toast("No app can open this file")
        }
    }

    // ----------------------------------------------------------------- Settings

    private fun openSettings() {
        if (!engine.isReady || recording != null || capturing) return
        cancelCountdown()
        val list = mutableListOf<Setting>()

        if (video) {
            val qualities = engine.supportedQualities()
            if (qualities.isNotEmpty()) {
                list += Setting(
                    "Resolution", qualities.map { qualityName(it) }, qualities.indexOf(quality)
                ) { applySetting { quality = qualities[it] } }
            }
            val rates = engine.supportedFps(front)
            list += Setting("Frames per second", rates.map { "$it" }, rates.indexOf(fps)) {
                applySetting { fps = rates[it] }
            }
        } else {
            val ratios = Aspect.values()
            list += Setting("Aspect ratio", ratios.map { it.label }, aspect.ordinal) {
                applySetting { aspect = ratios[it] }
            }
            list += Setting("Photo priority", listOf("Quality", "Speed"), if (fastShutter) 1 else 0) {
                applySetting { fastShutter = it == 1 }
            }
        }

        val timers = listOf(0, 3, 10)
        list += Setting("Timer", listOf("Off", "3s", "10s"), timers.indexOf(timerSeconds)) {
            applySetting(needsRebind = false) { timerSeconds = timers[it] }
        }
        list += Setting("Grid", listOf("Off", "On"), if (grid) 1 else 0) {
            applySetting(needsRebind = false) {
                grid = it == 1
                screen.overlay.showGrid = grid
            }
        }
        if (front) {
            list += Setting(
                "Save selfies as you see them", listOf("Mirrored", "Flipped"),
                if (mirrorSelfie) 0 else 1
            ) { applySetting(needsRebind = video) { mirrorSelfie = it == 0 } }
        }
        val stops = engine.isoStops()
        if (stops.isNotEmpty()) {
            val selected = iso?.let { stops.indexOf(it) + 1 } ?: 0
            list += Setting("ISO", listOf("Auto") + stops.map { "$it" }, selected) {
                applySetting(needsRebind = false) {
                    iso = if (it == 0) null else stops[it - 1]
                    engine.setIso(iso)
                }
            }
        }
        screen.sheet.show(list)
    }

    /** Apply one change, remember it and redraw the open sheet. */
    private fun applySetting(needsRebind: Boolean = true, change: () -> Unit) {
        change()
        if (needsRebind) rebind()
        savePreferences()
        openSettings()
    }

    private fun loadPreferences() {
        aspect = Aspect.values().getOrElse(prefs.getInt("aspect", 0)) { Aspect.FOUR_THREE }
        quality = qualityFromName(prefs.getString("quality", null))
        fps = prefs.getInt("fps", 30)
        timerSeconds = prefs.getInt("timer", 0)
        grid = prefs.getBoolean("grid", false)
        mirrorSelfie = prefs.getBoolean("mirror", true)
        fastShutter = prefs.getBoolean("fast", false)
    }

    private fun savePreferences() {
        prefs.edit()
            .putInt("aspect", aspect.ordinal)
            .putString("quality", qualityName(quality))
            .putInt("fps", fps)
            .putInt("timer", timerSeconds)
            .putBoolean("grid", grid)
            .putBoolean("mirror", mirrorSelfie)
            .putBoolean("fast", fastShutter)
            .apply()
    }

    private fun qualityName(value: Quality) = when (value) {
        Quality.UHD -> "4K"
        Quality.FHD -> "1080p"
        Quality.HD -> "720p"
        else -> "480p"
    }

    private fun qualityFromName(name: String?): Quality = when (name) {
        "4K" -> Quality.UHD
        "720p" -> Quality.HD
        "480p" -> Quality.SD
        else -> Quality.FHD
    }

    // ----------------------------------------------------------------- Rotation

    private fun onRotated(degrees: Int) {
        if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return
        // Change only when clearly past a diagonal, so it never flickers.
        val nearest = ((degrees + 45) / 90 * 90) % 360
        val distance = Math.abs((degrees - nearest + 540) % 360 - 180)
        if (distance > 35) return
        val rotation = when (nearest) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        if (rotation == engine.rotation) return
        engine.rotation = rotation
        val angle = when (rotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_270 -> -90f
            Surface.ROTATION_180 -> 180f
            else -> 0f
        }
        screen.rotatingViews.forEach { it.animate().rotation(angle).setDuration(220).start() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "Click"
        const val REQUEST_LAUNCH = 1001
        const val REQUEST_MICROPHONE = 1002
        const val REQUEST_ALBUM = 1003
        const val REQUEST_STORAGE = 1004
    }
}
