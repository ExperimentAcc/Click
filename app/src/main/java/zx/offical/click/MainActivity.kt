package zx.offical.click

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.FrameLayout
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExposureState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import zx.offical.click.ui.FocusOverlay
import zx.offical.click.ui.ModeScroller
import zx.offical.click.ui.ShutterButton
import zx.offical.click.util.ThumbnailLoader
import zx.offical.click.util.Ui

/**
 * Click — fast, iPhone-inspired camera.
 *
 * Architecture notes:
 * - Camera is bound strictly to the Activity lifecycle (bindToLifecycle), so the
 *   sensor and buffers release the moment the app is minimized/closed. There are
 *   no services or receivers anywhere in the app.
 * - Photo capture uses CAPTURE_MODE_MINIMIZE_LATENCY for zero shutter lag.
 * - Flash: ImageCapture.FLASH_MODE_* for photos (front-camera selfies get the
 *   bright white screen-flash via the overlay); torch for video.
 * - Tap-to-focus + exposure compensation via CameraControl.
 * - Manual ISO via Camera2Interop (Camera2CameraControl + SENSOR_SENSITIVITY).
 * - Zoom pills + pinch via CameraControl.setZoomRatio() in photo AND video.
 * - All media is saved straight to MediaStore (DCIM/Camera) so the Album picks
 *   it up instantly.
 */
class MainActivity : android.app.Activity() {

    companion object {
        private const val TAG = "Click"
        private const val REQ_PERMISSIONS = 1001
        private const val MODE_PHOTO = 0
        private const val MODE_VIDEO = 1
        private const val FLASH_OFF = 0
        private const val FLASH_ON = 1
        private const val FLASH_AUTO = 2
    }

    // ------------------------------------------------------------------ CameraX
    private lateinit var cameraProvider: ProcessCameraProvider
    private var cameraInfo: CameraInfo? = null
    private var cameraControl: CameraControl? = null

    private lateinit var preview: Preview
    private lateinit var imageCapture: ImageCapture
    private lateinit var videoCapture: VideoCapture
    private lateinit var recorder: Recorder

    private var videoQuality: Quality = Quality.FHD
    private var videoFps = 30
    private var activeRecording: Recording? = null

    // -------------------------------------------------------------------- State
    private var mode = MODE_PHOTO
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashState = FLASH_OFF
    private var aspectNum = 4
    private var aspectDen = 3
    private var isRecording = false
    private var currentZoom = 1.0f
    private var zoomRange = 1.0f..4.0f
    private var exposureRange = 0..0
    private var manualIso = false
    private var isoValue = 0
    private var isoMin = 100
    private var isoMax = 3200
    private var proDrawerOpen = false
    private var selectedVideoOption = 0
    private var lastMediaUri: Uri? = null

    private val mainExecutor = ContextCompat.getMainExecutor(this)
    private val handler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------- Pinch-to-zoom
    private var startZoom = 1.0f
    private val scaleDetector = ScaleGestureDetector(
        this,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                startZoom = currentZoom
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (startZoom * detector.scaleFactor)
                    .coerceIn(zoomRange.first, zoomRange.second)
                cameraControl?.setZoomRatio(target)
                currentZoom = target
                updateZoomPills()
                return true
            }
        }
    )

    // ----------------------------------------------------------------- Views
    private lateinit var previewView: PreviewView
    private lateinit var focusOverlay: FocusOverlay
    private lateinit var shutterFlash: View
    private lateinit var flashScreen: View

    private lateinit var flashPill: LinearLayout
    private lateinit var flashIcon: ImageView
    private lateinit var flashLabel: TextView
    private lateinit var aspectPill: LinearLayout
    private lateinit var aspectIcon: ImageView
    private lateinit var aspectLabel: TextView
    private lateinit var proPill: LinearLayout
    private lateinit var proIcon: ImageView
    private lateinit var resBadge: TextView
    private lateinit var recIndicator: LinearLayout
    private lateinit var recTimer: TextView

    private lateinit var modeScroller: ModeScroller
    private lateinit var shutterBtn: ShutterButton
    private lateinit var flipBtn: ImageView
    private lateinit var thumbView: ImageView
    private lateinit var zoom1x: TextView
    private lateinit var zoom2x: TextView
    private lateinit var zoom4x: TextView

    private lateinit var proDrawer: LinearLayout
    private lateinit var isoSeekBar: SeekBar
    private lateinit var isoLabel: TextView
    private lateinit var autoIsoBtn: TextView

    private val videoOptions = mutableListOf<VideoOption>()
    private var resPopup: PopupWindow? = null

    private data class VideoOption(val label: String, val quality: Quality, val fps: Int)

    // --------------------------------------------------------------- Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)

        bindViews()
        setupUi()

        if (hasCameraPermission()) {
            initCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), REQ_PERMISSIONS
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-establish the camera if permission was granted while the app was paused.
        if (!::cameraProvider.isInitialized && hasCameraPermission()) {
            initCamera()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::cameraProvider.isInitialized) {
            cameraProvider.shutdown()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                initCamera()
            } else {
                toast("Camera permission is required")
                finish()
            }
        }
    }

    // ----------------------------------------------------------------- Setup
    private fun bindViews() {
        previewView = findViewById(R.id.preview_view)
        focusOverlay = findViewById(R.id.focus_overlay)
        shutterFlash = findViewById(R.id.shutter_flash)
        flashScreen = findViewById(R.id.flash_screen)

        flashPill = findViewById(R.id.flash_pill)
        flashIcon = findViewById(R.id.flash_icon)
        flashLabel = findViewById(R.id.flash_label)
        aspectPill = findViewById(R.id.aspect_pill)
        aspectIcon = findViewById(R.id.aspect_icon)
        aspectLabel = findViewById(R.id.aspect_label)
        proPill = findViewById(R.id.pro_pill)
        proIcon = findViewById(R.id.pro_icon)
        resBadge = findViewById(R.id.res_badge)
        recIndicator = findViewById(R.id.rec_indicator)
        recTimer = findViewById(R.id.rec_timer)

        modeScroller = findViewById(R.id.mode_scroller)
        shutterBtn = findViewById(R.id.shutter_btn)
        flipBtn = findViewById(R.id.flip_btn)
        thumbView = findViewById(R.id.thumb_view)
        zoom1x = findViewById(R.id.zoom_1x)
        zoom2x = findViewById(R.id.zoom_2x)
        zoom4x = findViewById(R.id.zoom_4x)

        proDrawer = findViewById(R.id.pro_drawer)
        isoSeekBar = findViewById(R.id.iso_seekbar)
        isoLabel = findViewById(R.id.iso_label)
        autoIsoBtn = findViewById(R.id.auto_iso_btn)
    }

    private fun setupUi() {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        focusOverlay.scaleGesture = scaleDetector
        focusOverlay.onExposureChanged = { index ->
            cameraControl?.setExposureCompensationIndex(index)
        }

        flashPill.setOnClickListener { Ui.tick(it); cycleFlash() }
        aspectPill.setOnClickListener { Ui.tick(it); cycleAspect() }
        proPill.setOnClickListener { Ui.tick(it); toggleProDrawer() }
        resBadge.setOnClickListener { showResPopup() }

        modeScroller.onSelect = { video -> switchMode(video) }

        shutterBtn.setOnClickListener {
            Ui.confirm(it)
            if (mode == MODE_PHOTO) takePhoto() else toggleRecording()
        }
        flipBtn.setOnClickListener { flipCamera() }
        thumbView.setOnClickListener { openLastMedia() }

        zoom1x.setOnClickListener { setZoom(1.0f, it) }
        zoom2x.setOnClickListener { setZoom(2.0f, it) }
        zoom4x.setOnClickListener { setZoom(4.0f, it) }

        autoIsoBtn.setOnClickListener { Ui.tick(it); resetManualIso() }
        isoSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) setManualIso(progress + isoMin)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        updateZoomPills()
        updateFlashPill()
        updateAspectPill()
    }

    private fun initCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                cameraProvider = providerFuture.get()
                bindCamera()
            },
            mainExecutor
        )
    }

    // ------------------------------------------------------------- Camera bind
    @SuppressLint("MissingPermission")
    private fun bindCamera() {
        if (!hasCameraPermission()) return

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // NOTE: Preview.Builder.setMirrorMode() throws on CameraX 1.3.x, so front
        // preview mirroring is done on the PreviewView itself (scaleX = -1).
        preview = Preview.Builder()
            .setTargetAspectRatio(aspectRatio())
            .build()
        previewView.scaleX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) -1f else 1f

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(aspectRatio())
            .setFlashMode(flashImageMode())
            .build()

        videoCapture = buildVideoCapture(videoQuality, videoFps)

        cameraProvider.unbindAll()
        val useCases: List<UseCase> =
            if (mode == MODE_VIDEO) listOf(preview, videoCapture)
            else listOf(preview, imageCapture)

        try {
            cameraProvider.bindToLifecycle(this, selector, *useCases.toTypedArray())
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            toast("No usable camera found")
            return
        }

        preview.setSurfaceProvider(previewView.surfaceProvider)

        // CameraX 1.3.x exposes camera info through getAvailableCameraInfos().
        val available = cameraProvider.getAvailableCameraInfos()
        cameraInfo = available.firstOrNull { it.lensFacing == lensFacing }
            ?: available.firstOrNull()
        cameraControl = cameraInfo?.cameraControl

        observeCameraStates()
        refreshHardwareInfo()
        applyFlash()
        if (manualIso) applyManualIso(isoValue)
        updateTopBar()
    }

    /**
     * CameraX 1.3.x has no RATIO_SQUARE constant: the 1:1 mode streams at 4:3 and
     * relies on the PreviewView ViewPort (a square view + FILL_CENTER), which
     * CameraX applies as a crop to every bound use case — true ViewPort cropping.
     */
    private fun aspectRatio(): Int = when (aspectNum) {
        16 -> AspectRatio.RATIO_16_9
        else -> AspectRatio.RATIO_4_3
    }

    /**
     * Builds the VideoCapture with a Recorder (QualitySelector) + target frame
     * rate. Note: on CameraX 1.3.x, VideoCapture.Builder.setTargetAspectRatio /
     * setTargetResolution are unsupported — the quality selector + frame rate
     * Range are the supported configuration path.
     */
    private fun buildVideoCapture(quality: Quality, fps: Int): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.from(
            quality,
            FallbackStrategy.higherQualityOrLowerThan(quality)
        )
        recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        return try {
            VideoCapture.Builder(recorder)
                .setTargetFrameRate(Range.create(fps, fps))
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "VideoCapture build failed, falling back to FHD/30", e)
            recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
                    )
                )
                .build()
            VideoCapture.Builder(recorder)
                .setTargetFrameRate(Range.create(30, 30))
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .build()
        }
    }

    private fun qualityLabel(quality: Quality): String = when (quality) {
        Quality.SD, Quality.LOWEST -> "SD"
        Quality.HD -> "HD"
        Quality.FHD -> "FHD"
        else -> "UHD"
    }

    private fun qualityOfLabel(label: String): Quality = when (label) {
        "SD" -> Quality.SD
        "HD" -> Quality.HD
        "FHD" -> Quality.FHD
        else -> Quality.UHD
    }

    private fun observeCameraStates() {
        cameraInfo?.zoomState?.observe(this) { s: ZoomState ->
            currentZoom = s.zoomRatio
            zoomRange = s.minZoomRatio.coerceAtLeast(1f)..s.maxZoomRatio.coerceAtLeast(1f)
            updateZoomPills()
        }
    }

    // ------------------------------------------------- Hardware capability probing
    @OptIn(ExperimentalCamera2Interop::class)
    private fun refreshHardwareInfo() {
        val cam2 = cameraInfo?.let { Camera2CameraInfo.from(it) } ?: return

        // ISO range from the sensor characteristics (Camera2CameraInfo).
        val isoRange =
            cam2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoRange != null && isoRange.hasUpperBound()) {
            isoMin = isoRange.lower
            isoMax = isoRange.upper
        } else {
            isoMin = 100
            isoMax = 3200
        }
        if (isoSeekBar.max != (isoMax - isoMin)) {
            isoSeekBar.max = isoMax - isoMin
        }

        // Exposure compensation range from CameraInfo.exposureState.
        val exposureState: ExposureState? = cameraInfo?.exposureState
        if (exposureState != null && exposureState.isExposureCompensationSupported) {
            val r = exposureState.exposureCompensationRange
            exposureRange = if (r != null) r.lower..r.upper else 0..0
            focusOverlay.exposureRange = exposureRange
            if (exposureRange.isNotEmpty()) {
                cameraControl?.setExposureCompensationIndex(
                    exposureState.exposureCompensationIndex
                )
            }
        }

        // Resolutions via CameraX QualitySelector; frame rates via the device's
        // supported AE frame-rate ranges (30 fps is always offered as a baseline).
        videoOptions.clear()
        val qualities = try {
            QualitySelector.getSupportedQualities(cameraInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Supported qualities query failed", e)
            emptyList()
        }
        val fpsChoices = try {
            (cameraInfo?.supportedFrameRateRanges
                ?.flatMap { range -> listOf(range.lower, range.upper) }
                ?.filter { it >= 15 }
                ?.toSortedSet()
                ?: emptySet()) + 30
        } catch (e: Exception) {
            Log.w(TAG, "Frame rate range query failed", e)
            setOf(30)
        }.toSortedSet().toList()

        val combos = linkedSetOf<Pair<String, Int>>()
        qualities.forEach { quality ->
            val label = qualityLabel(quality)
            fpsChoices.forEach { fps -> combos.add(label to fps) }
        }
        if (combos.isEmpty()) combos.add("FHD" to 30)

        videoOptions.addAll(
            combos.map { (label, fps) -> VideoOption(label, qualityOfLabel(label), fps) }
        )
        val fhd30 = videoOptions.indexOfFirst { it.label == "FHD" && it.fps == 30 }
        selectedVideoOption = if (fhd30 >= 0) fhd30 else 0
    }

    // ---------------------------------------------------------------------- Flash
    /**
     * CameraX 1.3.x ImageCapture has no FLASH_MODE_SCREEN: the front-camera
     * "bright white screen-flash" is produced by the white overlay in
     * [takePhoto] (whiteFlash()), while rear-camera ON uses the hardware flash.
     */
    private fun flashImageMode(): Int = when (flashState) {
        FLASH_OFF -> ImageCapture.FLASH_MODE_OFF
        FLASH_ON -> if (lensFacing == CameraSelector.LENS_FACING_FRONT)
            ImageCapture.FLASH_MODE_OFF
        else
            ImageCapture.FLASH_MODE_ON
        FLASH_AUTO -> ImageCapture.FLASH_MODE_AUTO
        else -> ImageCapture.FLASH_MODE_OFF
    }

    private fun applyFlash() {
        val ctrl = cameraControl ?: return
        if (mode == MODE_VIDEO) {
            ctrl.enableTorch(flashState == FLASH_ON)
        } else {
            imageCapture.flashMode = flashImageMode()
        }
    }

    private fun cycleFlash() {
        flashState = when (flashState) {
            FLASH_OFF -> FLASH_ON
            FLASH_ON -> FLASH_AUTO
            else -> FLASH_OFF
        }
        updateFlashPill()
        applyFlash()
    }

    private fun updateFlashPill() {
        flashLabel.text = when (flashState) {
            FLASH_OFF -> "OFF"
            FLASH_ON -> "ON"
            else -> "AUTO"
        }
        val active = flashState != FLASH_OFF
        pillActive(flashPill, flashIcon, flashLabel, active)
    }

    // -------------------------------------------------------------- Aspect ratio
    private fun cycleAspect() {
        aspectNum = when {
            aspectNum == 4 -> 16
            aspectNum == 16 -> 1
            else -> 4
        }
        aspectDen = when (aspectNum) {
            4 -> 3
            16 -> 9
            else -> 1
        }
        updateAspectPill()
        animateAspect()
        bindCamera()
    }

    private fun updateAspectPill() {
        aspectLabel.text = "${aspectNum}:${aspectDen}"
        pillActive(aspectPill, aspectIcon, aspectLabel, false)
    }

    /** Animate the viewfinder height so the crop ratio changes smoothly. */
    private fun animateAspect() {
        val targetH = if (previewView.width > 0) {
            (previewView.width.toFloat() * aspectDen / aspectNum).toInt()
        } else 0
        if (targetH <= 0) return
        val lp = previewView.layoutParams as FrameLayout.LayoutParams
        ValueAnimator.ofInt(lp.height, targetH)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .addUpdateListener { a ->
                lp.height = a.animatedValue as Int
                previewView.requestLayout()
            }
            .start()
    }

    // --------------------------------------------------------------- Zoom
    private fun setZoom(ratio: Float, view: View) {
        Ui.tick(view)
        val target = ratio.coerceIn(zoomRange.first, zoomRange.second)
        cameraControl?.setZoomRatio(target)
        currentZoom = target
        updateZoomPills()
    }

    private fun updateZoomPills() {
        setZoomPillActive(zoom1x, abs(currentZoom - 1f) < 0.2f)
        setZoomPillActive(zoom2x, abs(currentZoom - 2f) < 0.2f)
        setZoomPillActive(zoom4x, abs(currentZoom - 4f) < 0.2f)
    }

    private fun setZoomPillActive(tv: TextView, active: Boolean) {
        tv.setBackgroundResource(
            if (active) R.drawable.bg_zoom_pill_active else R.drawable.bg_zoom_pill
        )
        tv.setTextColor(if (active) 0xFFFFD60A.toInt() else 0xFF8E8E93.toInt())
    }

    // --------------------------------------------------------------- Mode switch
    private fun switchMode(video: Boolean) {
        if (video == (mode == MODE_VIDEO)) return
        Ui.tick(modeScroller)
        if (isRecording) stopRecording(showToast = false)

        mode = if (video) MODE_VIDEO else MODE_PHOTO
        modeScroller.setVideo(video, animate = true)
        shutterBtn.setState(
            if (video) ShutterButton.State.VIDEO else ShutterButton.State.PHOTO,
            animate = true
        )
        bindCamera()
    }

    // ------------------------------------------------------------- Photo capture
    private fun takePhoto() {
        Ui.confirm(shutterBtn)
        // Bright white screen-flash for front-camera selfies.
        if (lensFacing == CameraSelector.LENS_FACING_FRONT && flashState == FLASH_ON) {
            whiteFlash()
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + Ui.mediaTimestamp())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )
            .build()

        imageCapture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    onMediaSaved(result.savedUri)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exc)
                    toast("Capture failed")
                }
            }
        )
        shutterFlash()
    }

    // ----------------------------------------------------------- Video recording
    private fun toggleRecording() {
        if (isRecording) stopRecording(showToast = true) else startRecording()
    }

    private fun startRecording() {
        if (mode != MODE_VIDEO) return
        Ui.confirm(shutterBtn)

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_" + Ui.mediaTimestamp())
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val options = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(values)
            .build()

        val pending = recorder.prepareRecording(this, options)
        activeRecording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (event.hasError()) {
                    Log.e(TAG, "Recording failed with code ${event.outputResults}")
                    isRecording = false
                    updateRecordingUi(false)
                    toast("Recording failed")
                } else {
                    onMediaSaved(event.outputResults.outputUri)
                }
            }
        }

        isRecording = true
        updateRecordingUi(true)
        shutterFlash()
    }

    private fun stopRecording(showToast: Boolean) {
        if (!isRecording) return
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        updateRecordingUi(false)
        shutterFlash()
        if (showToast) toast("Video saved")
    }

    private fun updateRecordingUi(recording: Boolean) {
        recIndicator.visibility = if (recording) View.VISIBLE else View.GONE
        shutterBtn.setState(
            if (recording) ShutterButton.State.RECORDING else ShutterButton.State.VIDEO,
            animate = true
        )
        if (recording) startRecTimer() else stopRecTimer()
    }

    private val recTickRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = System.currentTimeMillis() - recStartMs
            recTimer.text = formatTime(elapsed)
            handler.postDelayed(this, 250)
        }
    }

    private var recStartMs = 0L

    private fun startRecTimer() {
        recStartMs = System.currentTimeMillis()
        handler.post(recTickRunnable)
    }

    private fun stopRecTimer() {
        handler.removeCallbacks(recTickRunnable)
        recTimer.text = "00:00"
    }

    private fun formatTime(elapsedMs: Long): String {
        val totalSec = elapsedMs / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    // ------------------------------------------------------------- Resolution UI
    private fun updateTopBar() {
        val opt = videoOptions.getOrNull(selectedVideoOption)
        if (mode == MODE_VIDEO && opt != null) {
            resBadge.text = "${opt.label} • ${opt.fps}"
            resBadge.visibility = View.VISIBLE
        } else {
            resBadge.visibility = View.GONE
        }
    }

    private fun showResPopup() {
        if (videoOptions.isEmpty()) {
            toast("No high-speed video modes detected")
            return
        }
        if (isRecording) {
            toast("Stop recording to change resolution")
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        videoOptions.forEach { opt ->
            val btn = TextView(this).apply {
                text = "${opt.label} • ${opt.fps}"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(24), dp(11), dp(24), dp(11))
                setBackgroundResource(R.drawable.bg_popup_item)
                setOnClickListener {
                    Ui.tick(this)
                    selectVideoOption(opt)
                }
            }
            container.addView(btn)
        }

        resPopup?.dismiss()
        resPopup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = resources.displayMetrics.density * 12f
            setOutsideTouchable(true)
        }
        resPopup?.showAsDropDown(resBadge, 0, 0)
    }

    private fun selectVideoOption(opt: VideoOption) {
        selectedVideoOption = videoOptions.indexOf(opt)
        videoQuality = opt.quality
        videoFps = opt.fps
        updateTopBar()
        resPopup?.dismiss()
        if (mode == MODE_VIDEO && !isRecording) {
            videoCapture = buildVideoCapture(videoQuality, videoFps)
            bindCamera()
        }
    }

    // ------------------------------------------------------------ Manual ISO
    private fun toggleProDrawer() {
        proDrawerOpen = !proDrawerOpen
        val h = resources.getDimensionPixelSize(R.dimen.pro_drawer_height)
        proDrawer.animate()
            .translationY(if (proDrawerOpen) 0f else h.toFloat())
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
        isoSeekBar.isEnabled = proDrawerOpen
        pillActive(proPill, proIcon, null, proDrawerOpen)
    }

    private fun setManualIso(iso: Int) {
        isoValue = iso.coerceIn(isoMin, isoMax)
        isoLabel.text = "ISO $isoValue"
        applyManualIso(isoValue)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualIso(iso: Int) {
        val ctrl = cameraControl ?: return
        val c2 = Camera2CameraControl.from(ctrl)
        c2.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                .build()
        )
        manualIso = true
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun resetManualIso() {
        val ctrl = cameraControl ?: return
        Camera2CameraControl.from(ctrl).clearCaptureRequestOptions()
        manualIso = false
        isoValue = 0
        isoLabel.text = "ISO AUTO"
        isoSeekBar.progress = 0
    }

    // -------------------------------------------------------------- Flip camera
    private fun flipCamera() {
        Ui.tick(flipBtn)
        ObjectAnimator.ofFloat(flipBtn, "rotation", flipBtn.rotation, flipBtn.rotation + 180f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .start()
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK
        applyFlash()
        bindCamera()
    }

    // ------------------------------------------------------------- Media & flash
    private fun onMediaSaved(uri: Uri?) {
        if (uri == null) return
        lastMediaUri = uri
        ThumbnailLoader.load(contentResolver, uri, thumbView)
        pop(thumbView)
    }

    private fun pop(view: View) {
        view.animate()
            .scaleX(1.18f)
            .scaleY(1.18f)
            .setDuration(110)
            .withEndAction {
                view.scaleX = 1f
                view.scaleY = 1f
            }
            .start()
    }

    private fun openLastMedia() {
        val uri = lastMediaUri ?: return
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_GRANT_READ_PERMISSION)
        }
        val type = try {
            androidx.core.content.MediaStore.getType(contentResolver, uri)
        } catch (e: Exception) {
            null
        }
        if (type != null) intent.setDataAndType(uri, type) else intent.setData(uri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Cannot open media")
        }
    }

    /** Instant black-out shutter flash — zero capture lag. */
    private fun shutterFlash() {
        shutterFlash.alpha = 1f
        shutterFlash.visibility = View.VISIBLE
        shutterFlash.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction { shutterFlash.visibility = View.INVISIBLE }
            .start()
    }

    private fun whiteFlash() {
        flashScreen.alpha = 1f
        flashScreen.visibility = View.VISIBLE
        flashScreen.animate()
            .alpha(0f)
            .setDuration(170)
            .withEndAction { flashScreen.visibility = View.INVISIBLE }
            .start()
    }

    // ------------------------------------------------------------------ Helpers
    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun pillActive(
        container: LinearLayout,
        icon: ImageView,
        label: TextView?,
        active: Boolean
    ) {
        container.setBackgroundResource(
            if (active) R.drawable.bg_pill_active else R.drawable.bg_pill
        )
        icon.imageTintList = ColorStateList.valueOf(
            if (active) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
        )
        label?.setTextColor(
            if (active) 0xFFFFD60A.toInt() else 0xFF8E8E93.toInt()
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
