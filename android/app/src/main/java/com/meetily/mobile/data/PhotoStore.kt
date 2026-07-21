package com.meetily.mobile.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Photos live in filesDir/photos, referenced by filename from Meeting.photos. */
object PhotoStore {

    private const val AUTHORITY = "com.meetily.mobile.fileprovider"

    fun dir(context: Context): File =
        File(context.filesDir, "photos").apply { mkdirs() }

    fun newPhotoFile(context: Context, meetingId: String): File =
        File(dir(context), "${meetingId}_${System.currentTimeMillis()}.jpg")

    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, AUTHORITY, file)

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
}
