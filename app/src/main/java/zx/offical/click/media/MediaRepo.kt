package zx.offical.click.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.File
import zx.offical.click.util.Ui

/** Media permissions, DCIM/Camera output and the "latest album item" thumbnail. */
object MediaRepo {

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Everything worth asking for at launch. Only CAMERA is mandatory. */
    fun launchPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
            if (Build.VERSION.SDK_INT >= 34) {
                list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT <= 28) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        return list
    }

    fun missing(context: Context): List<String> =
        launchPermissions().filterNot { granted(context, it) }

    fun hasCamera(context: Context) = granted(context, Manifest.permission.CAMERA)

    fun hasMicrophone(context: Context) = granted(context, Manifest.permission.RECORD_AUDIO)

    /** Android 8 and 9 need storage permission to write into the shared album. */
    fun canSave(context: Context) =
        Build.VERSION.SDK_INT >= 29 || granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun hasAlbumAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> true
        Build.VERSION.SDK_INT >= 33 ->
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun photoValues(): ContentValues = values("IMG_", ".jpg", "image/jpeg")

    fun videoValues(): ContentValues = values("VID_", ".mp4", "video/mp4")

    @Suppress("DEPRECATION")
    private fun values(prefix: String, extension: String, mime: String): ContentValues {
        val name = prefix + Ui.mediaTimestamp() + extension
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera"
                )
                dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
            }
        }
    }

    /** Newest photo or video under DCIM. Call off the main thread. */
    @Suppress("DEPRECATION")
    fun latest(resolver: ContentResolver): Uri? {
        val files = MediaStore.Files.getContentUri("external")
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val pathColumn =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH
            else MediaStore.MediaColumns.DATA
        val pathPattern = if (Build.VERSION.SDK_INT >= 29) "DCIM/%" else "%/DCIM/%"
        return try {
            resolver.query(
                files,
                arrayOf(MediaStore.MediaColumns._ID, type),
                "$type IN (?, ?) AND $pathColumn LIKE ?",
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                    pathPattern
                ),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val base =
                    if (cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                ContentUris.withAppendedId(base, cursor.getLong(0))
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Small preview of a photo or video. Call off the main thread. */
    fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? = try {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } else if (resolver.getType(uri)?.startsWith("video") == true) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.frameAtTime?.let { Bitmap.createScaledBitmap(it, sizePx, sizePx, true) }
            } finally {
                retriever.release()
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options()
            options.inSampleSize =
                (minOf(bounds.outWidth, bounds.outHeight) / sizePx).coerceAtLeast(1)
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (_: Exception) {
        null
    }
}
