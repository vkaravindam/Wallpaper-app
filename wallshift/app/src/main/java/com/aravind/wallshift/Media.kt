package com.aravind.wallshift

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Helpers for the kinds of files WallShift handles: images, GIF/WebP animations, videos. */
object Media {
    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "3gp", "mov")
    private val ANIM_EXT = setOf("gif", "webp")

    fun isVideo(f: File) = f.extension.lowercase() in VIDEO_EXT
    fun mayBeAnimated(f: File) = f.extension.lowercase() in ANIM_EXT

    /** Short label shown on thumbnails, or null for plain images. */
    fun label(f: File): String? = when {
        isVideo(f) -> "VIDEO"
        f.extension.lowercase() == "gif" -> "GIF"
        else -> null
    }

    /** File extension to store an imported item under, based on its MIME type. */
    fun extensionFor(mime: String?): String {
        if (mime == null) return "img"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()
        return when {
            mime == "image/gif" -> "gif"
            mime == "image/webp" -> "webp"
            mime.startsWith("video/") -> if (ext != null && ext in VIDEO_EXT) ext else "mp4"
            else -> ext ?: "img"
        }
    }

    /** Decodes an image with subsampling so large photos don't use too much memory. */
    fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A still image for any file: the image itself, the first GIF frame, or a video frame. */
    fun stillFrame(file: File, reqW: Int, reqH: Int): Bitmap? {
        if (!isVideo(file)) return decodeSampled(file, reqW, reqH)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val s = max(reqW.toFloat() / frame.width, reqH.toFloat() / frame.height)
            if (s < 0.5f) {
                val scaled = Bitmap.createScaledBitmap(
                    frame, (frame.width * s).roundToInt().coerceAtLeast(1),
                    (frame.height * s).roundToInt().coerceAtLeast(1), true
                )
                frame.recycle()
                scaled
            } else frame
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Video size as displayed (rotation applied), or null if unknown. */
    fun videoDisplaySize(file: File): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0 || h <= 0) null
            else if (rot == 90 || rot == 270) h to w else w to h
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /**
     * Scales [src] so it covers a w×h screen. Images wider than the screen keep their extra
     * width (capped at [maxW]) so they can pan across home screen pages.
     */
    fun scaleToCover(src: Bitmap, w: Int, h: Int, maxW: Int): Bitmap {
        val s = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val outW = (src.width * s).roundToInt().coerceAtLeast(1)
        val outH = (src.height * s).roundToInt().coerceAtLeast(1)
        val scaled = if (outW == src.width && outH == src.height) src
        else Bitmap.createScaledBitmap(src, outW, outH, true)
        val cropW = min(outW, maxW)
        val cropH = min(outH, h)
        if (cropW == outW && cropH == outH) return scaled
        val cropped = Bitmap.createBitmap(scaled, (outW - cropW) / 2, (outH - cropH) / 2, cropW, cropH)
        if (scaled !== src && scaled !== cropped) scaled.recycle()
        return cropped
    }
}
