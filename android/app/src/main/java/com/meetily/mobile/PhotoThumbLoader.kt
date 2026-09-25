package com.meetily.mobile

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.meetily.mobile.data.PhotoSampling
import com.meetily.mobile.data.PhotoStore
import java.io.File
import java.util.concurrent.Executors

/**
 * Decodes photo thumbnails and the full-screen viewer bitmap off the main
 * thread. Small thumbnails stay in a process-wide LruCache sized by bytes, so
 * re-rendering the meeting screen (after an edit, a sync or a rotation) does
 * not decode every photo again.
 */
object PhotoThumbLoader {

    private val cache = object : LruCache<String, Bitmap>(
        PhotoSampling.cacheSizeKb(Runtime.getRuntime().maxMemory())
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** Two workers: enough to fill a strip quickly without holding many full decodes at once. */
    private val worker = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "photo-thumbs").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private fun key(file: File, sizePx: Int) = "${file.absolutePath}#$sizePx"

    /**
     * Shows [file] in [view] as a [sizePx]-square thumbnail. A cached one is
     * set at once; otherwise the decode runs on a worker and the result is
     * set only if [view] still shows this photo (its tag) and [activity] is
     * still alive. [onMissing] runs on the main thread, under the same
     * checks, when the photo cannot be decoded.
     */
    fun loadThumb(
        activity: Activity,
        view: ImageView,
        file: File,
        sizePx: Int,
        onMissing: (() -> Unit)? = null
    ) {
        val key = key(file, sizePx)
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        worker.execute {
            val bitmap = try {
                PhotoStore.decodeForView(file, sizePx, sizePx)
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) cache.put(key, bitmap)
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (view.tag != key) return@post
                if (bitmap != null) view.setImageBitmap(bitmap) else onMissing?.invoke()
            }
        }
    }

    /**
     * Decodes [file] downsampled to fit the screen and hands it to [onReady]
     * on the main thread, only while [activity] is alive; null when the
     * photo cannot be read.
     */
    fun loadFull(activity: Activity, file: File, onReady: (Bitmap?) -> Unit) {
        val metrics = activity.resources.displayMetrics
        val w = metrics.widthPixels.coerceAtLeast(1)
        val h = metrics.heightPixels.coerceAtLeast(1)
        worker.execute {
            val bitmap = try {
                PhotoStore.decodeForView(file, w, h, fit = true)
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                onReady(bitmap)
            }
        }
    }

    /** Drops every cached thumbnail of [file], e.g. after it is deleted. */
    fun evict(file: File) {
        val prefix = file.absolutePath + "#"
        for (k in cache.snapshot().keys) {
            if (k.startsWith(prefix)) cache.remove(k)
        }
    }
}
