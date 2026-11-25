package zx.offical.click.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView

/**
 * Decodes a MediaStore thumbnail without any third-party library.
 * Uses inJustDecodeBounds + inSampleSize so even 4K images decode in a few ms.
 */
object ThumbnailLoader {

    fun load(resolver: ContentResolver, uri: Uri, target: ImageView, sizeDp: Int = 96) {
        val req = Ui.dp(target.context, sizeDp)
        val bitmap = decode(resolver, uri, req) ?: return
        target.setImageBitmap(bitmap)
    }

    private fun decode(resolver: ContentResolver, uri: Uri, req: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val outW = bounds.outWidth
            val outH = bounds.outHeight
            if (outW <= 0 || outH <= 0) return null

            var sample = 1
            var w = outW
            var h = outH
            while (w / sample > req || h / sample > req) {
                sample *= 2
                w /= 2
                h /= 2
            }

            val sampled = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, sampled)
            }
        } catch (_: Exception) {
            null
        }
    }
}
