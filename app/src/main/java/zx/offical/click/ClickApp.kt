package zx.offical.click

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig

/**
 * Supplies the CameraX configuration directly, so CameraX never has to look it up
 * in MetadataHolderService (removed in the manifest). CameraX initializes lazily
 * on the first ProcessCameraProvider.getInstance() call.
 */
class ClickApp : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig = Camera2Config.defaultConfig()
}
