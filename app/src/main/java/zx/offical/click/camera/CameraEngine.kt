package zx.offical.click.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.util.concurrent.TimeUnit

enum class Aspect(val label: String, val heightOverWidth: Float) {
    FOUR_THREE("4:3", 4f / 3f),
    WIDE("16:9", 16f / 9f),
    SQUARE("1:1", 1f)
}

data class CameraConfig(
    val video: Boolean,
    val front: Boolean,
    val aspect: Aspect,
    val quality: Quality,
    val fps: Int,
    val mirrorSelfie: Boolean,
    val fastShutter: Boolean
)

/** Owns every CameraX object. Only the use cases for the current mode are bound. */
class CameraEngine(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onZoomChanged: (min: Float, max: Float, current: Float) -> Unit
) {
    private var provider: ProcessCameraProvider? = null
    private var starting = false
    private var camera: Camera? = null

    var imageCapture: ImageCapture? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    /** Surface.ROTATION_* matching how the phone is physically held. */
    var rotation: Int = Surface.ROTATION_0
        set(value) {
            field = value
            imageCapture?.targetRotation = value
            videoCapture?.targetRotation = value
        }

    val isReady get() = camera != null
    val hasFlashUnit get() = camera?.cameraInfo?.hasFlashUnit() == true

    private val zoomObserver = Observer<ZoomState> {
        onZoomChanged(it.minZoomRatio, it.maxZoomRatio, it.zoomRatio)
    }

    fun start(onReady: () -> Unit, onError: () -> Unit) {
        if (provider != null) {
            onReady()
            return
        }
        if (starting) return
        starting = true
        val fail = { e: Exception ->
            Log.e(TAG, "Camera provider failed", e)
            starting = false
            onError()
        }
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        provider = future.get()
                        starting = false
                        onReady()
                    } catch (e: Exception) {
                        fail(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun hasLens(front: Boolean): Boolean = try {
        provider?.hasCamera(selector(front)) == true
    } catch (_: Exception) {
        false
    }

    private fun selector(front: Boolean) =
        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    /** Returns false if this device rejects the requested combination. */
    fun bind(config: CameraConfig): Boolean {
        val cameraProvider = provider ?: return false
        release()

        // Video is always 16:9; photos follow the chosen ratio (1:1 crops a 4:3 stream).
        val wide = config.video || config.aspect == Aspect.WIDE
        val ratio =
            if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

        return try {
            // PreviewView mirrors the front camera by itself, so no manual flip here.
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
                )
                .build()
            val group = UseCaseGroup.Builder().addUseCase(preview)

            if (config.video) {
                val recorder = Recorder.Builder()
                    .setAspectRatio(AspectRatio.RATIO_16_9)
                    .setQualitySelector(
                        QualitySelector.from(
                            config.quality,
                            FallbackStrategy.higherQualityOrLowerThan(config.quality)
                        )
                    )
                    .build()
                val builder = VideoCapture.Builder(recorder)
                    .setMirrorMode(
                        if (config.mirrorSelfie) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY
                        else MirrorMode.MIRROR_MODE_OFF
                    )
                frameRateRange(config.front, config.fps)?.let { builder.setTargetFrameRate(it) }
                videoCapture = builder.build().also {
                    it.targetRotation = rotation
                    group.addUseCase(it)
                }
            } else {
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(
                        if (config.fastShutter) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    )
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .setTargetRotation(rotation)
                    .build()
                    .also { group.addUseCase(it) }
                if (config.aspect == Aspect.SQUARE) {
                    group.setViewPort(
                        ViewPort.Builder(Rational(1, 1), Surface.ROTATION_0).build()
                    )
                }
            }

            preview.setSurfaceProvider(previewView.surfaceProvider)
            val bound = cameraProvider.bindToLifecycle(owner, selector(config.front), group.build())
            camera = bound
            bound.cameraInfo.zoomState.observe(owner, zoomObserver)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Binding failed for $config", e)
            release()
            false
        }
    }

    fun release() {
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        camera = null
        imageCapture = null
        videoCapture = null
        provider?.unbindAll()
    }

    // ------------------------------------------------------------ Capabilities

    @Suppress("DEPRECATION")
    fun supportedQualities(): List<Quality> {
        val info = camera?.cameraInfo ?: return emptyList()
        return try {
            QualitySelector.getSupportedQualities(info)
        } catch (e: Exception) {
            Log.w(TAG, "Quality query failed", e)
            emptyList()
        }
    }

    private fun frameRateRanges(front: Boolean): Set<Range<Int>> = try {
        val info = provider?.availableCameraInfos?.let { selector(front).filter(it).firstOrNull() }
        info?.supportedFrameRateRanges ?: emptySet()
    } catch (e: Exception) {
        Log.w(TAG, "Frame rate query failed", e)
        emptySet()
    }

    /** Prefer a locked rate, otherwise the narrowest range that tops out at [fps]. */
    private fun frameRateRange(front: Boolean, fps: Int): Range<Int>? =
        frameRateRanges(front).filter { it.upper == fps }.maxByOrNull { it.lower }

    fun supportedFps(front: Boolean): List<Int> {
        val tops = frameRateRanges(front).map { it.upper }.toSet()
        return listOf(24, 30, 60).filter { it == 30 || it in tops }
    }

    // ----------------------------------------------------------------- Controls

    fun setZoom(ratio: Float) {
        val current = camera ?: return
        val state = current.cameraInfo.zoomState.value ?: return
        current.cameraControl.setZoomRatio(ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    fun zoomBy(factor: Float) {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        setZoom(state.zoomRatio * factor)
    }

    fun setTorch(on: Boolean) {
        if (hasFlashUnit) camera?.cameraControl?.enableTorch(on)
    }

    fun focusAt(x: Float, y: Float) {
        val current = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        if (current.cameraInfo.isFocusMeteringSupported(action)) {
            current.cameraControl.startFocusAndMetering(action)
        }
    }

    fun exposureRange(): IntRange {
        val state = camera?.cameraInfo?.exposureState ?: return 0..0
        if (!state.isExposureCompensationSupported) return 0..0
        return state.exposureCompensationRange.lower..state.exposureCompensationRange.upper
    }

    fun setExposure(index: Int) {
        if (index in exposureRange()) camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    /** ISO stops this sensor accepts; empty when manual control is unsupported. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun isoStops(): List<Int> {
        val info = camera?.cameraInfo ?: return emptyList()
        val camera2 = Camera2CameraInfo.from(info)
        val capabilities = camera2.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: return emptyList()
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        if (!capabilities.contains(manual)) return emptyList()
        val range = camera2.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        ) ?: return emptyList()
        return listOf(100, 200, 400, 800, 1600, 3200).filter { it >= range.lower && it <= range.upper }
    }

    /** null restores auto exposure. Manual ISO pairs with a fixed 1/60 s shutter. */
    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalCamera2Interop::class)
    fun setIso(iso: Int?) {
        val control = camera?.cameraControl ?: return
        val camera2 = Camera2CameraControl.from(control)
        if (iso == null) {
            camera2.clearCaptureRequestOptions()
            return
        }
        camera2.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, SHUTTER_NS)
                .build()
        )
    }

    private companion object {
        const val TAG = "Click"
        const val SHUTTER_NS = 16_666_667L
    }
}
