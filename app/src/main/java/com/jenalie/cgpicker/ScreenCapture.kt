package com.jenalie.cgpicker

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import java.nio.ByteBuffer

/**
 * Mirrors the screen at half size into an ImageReader and, about once a second, reads the
 * Color Game results strip from it. [onRead] gets the strip (or null when it isn't visible)
 * on the main thread. [onStopped] runs if Android or the user ends the capture.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val onRead: (List<Round>?) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("cg-screen-capture").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var lastSampleAt = 0L
    @Volatile private var released = false

    fun start() {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!released) {
                    release()
                    onStopped()
                }
            }
        }, main)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .getRealMetrics(metrics)
        val w = maxOf(1, metrics.widthPixels / 2)
        val h = maxOf(1, metrics.heightPixels / 2)

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val image = try { ir.acquireLatestImage() } catch (e: RuntimeException) { null }
                ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSampleAt < SAMPLE_EVERY_MS) return@setOnImageAvailableListener
                lastSampleAt = now
                val plane = image.planes[0]
                val strip = ResultStripReader.read(
                    BufferPixels(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
                )
                main.post { if (!released) onRead(strip) }
            } finally {
                image.close()
            }
        }, worker)
        reader = r
        display = projection.createVirtualDisplay(
            "cg-picker-reader", w, h, metrics.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, worker,
        )
    }

    fun release() {
        if (released) return
        released = true
        display?.release()
        display = null
        projection.stop()
        // Close the reader on its own thread so no frame is being read while it closes.
        worker.post {
            reader?.close()
            reader = null
            thread.quitSafely()
        }
    }

    /** RGBA_8888 image plane. */
    private class BufferPixels(
        private val buf: ByteBuffer,
        private val rowStride: Int,
        private val pixelStride: Int,
        override val width: Int,
        override val height: Int,
    ) : Pixels {
        override fun rgb(x: Int, y: Int): Int {
            val i = y * rowStride + x * pixelStride
            val r = buf.get(i).toInt() and 0xFF
            val g = buf.get(i + 1).toInt() and 0xFF
            val b = buf.get(i + 2).toInt() and 0xFF
            return (r shl 16) or (g shl 8) or b
        }
    }

    companion object {
        private const val SAMPLE_EVERY_MS = 1000L
    }
}
