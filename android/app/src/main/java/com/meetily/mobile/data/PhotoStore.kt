package com.meetily.mobile.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Photos live in filesDir/photos, referenced by filename from Meeting.photos. */
object PhotoStore {

    private fun authority(context: Context) = context.packageName + ".fileprovider"

    fun dir(context: Context): File =
        File(context.filesDir, "photos").apply { mkdirs() }

    fun newPhotoFile(context: Context, meetingId: String): File =
        File(dir(context), "${meetingId}_${System.currentTimeMillis()}.jpg")

    // Names come from meeting JSON, which a restored backup controls.
    fun fileFor(context: Context, name: String): File = SafeFiles.child(dir(context), name)

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    fun delete(context: Context, name: String) {
        fileFor(context, name).delete()
    }

    /** Decodes a bitmap downsampled so its larger side is roughly [targetPx]. */
    fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var larger = maxOf(bounds.outWidth, bounds.outHeight)
        while (larger / 2 >= targetPx) {
            sample *= 2
            larger /= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /**
     * Decodes [file] for display in a view of about [reqWidth] x [reqHeight]
     * pixels: a bounds-only pass first, then a decode with the largest
     * power-of-two inSampleSize that still fills the view (centre-crop, or
     * with [fit] fits inside it), then the EXIF orientation applied.
     * Blocking; call off the main thread.
     */
    fun decodeForView(file: File, reqWidth: Int, reqHeight: Int, fit: Boolean = false): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val degrees = rotationDegrees(file)
        // The request is in display orientation; the stored pixels are not.
        val swap = degrees == 90 || degrees == 270
        val rw = if (swap) reqHeight else reqWidth
        val rh = if (swap) reqWidth else reqHeight
        val sample = if (fit) {
            PhotoSampling.inSampleSizeToFit(bounds.outWidth, bounds.outHeight, rw, rh)
        } else {
            PhotoSampling.inSampleSize(bounds.outWidth, bounds.outHeight, rw, rh)
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        if (degrees == 0) return decoded
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true
            )
            if (rotated !== decoded) decoded.recycle()
            rotated
        } catch (_: OutOfMemoryError) {
            decoded
        }
    }

    /** Clockwise rotation the EXIF orientation tag asks for; 0 when absent. */
    fun rotationDegrees(file: File): Int = try {
        when (
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}
