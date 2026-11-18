package com.aravind.wallshift

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import kotlin.math.max
import kotlin.math.min

object WallpaperSetter {

    /** Physical screen size in portrait orientation (width < height). */
    private fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        return min(w, h) to max(w, h)
    }

    /** Decodes with subsampling so huge camera photos don't blow up memory. */
    private fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    /** Scales and center-crops the image to exactly fill the screen. */
    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dx = (w - src.width * scale) / 2f
        val dy = (h - src.height * scale) / 2f
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Sets [file] as the wallpaper on the chosen screen(s). Returns true on success. */
    fun apply(context: Context, file: File, target: WallTarget): Boolean {
        return try {
            val (w, h) = screenSize(context)
            val raw = decodeSampled(file, w, h) ?: return false
            val bmp = centerCrop(raw, w, h)
            if (bmp !== raw) raw.recycle()

            val flags = when (target) {
                WallTarget.HOME -> WallpaperManager.FLAG_SYSTEM
                WallTarget.LOCK -> WallpaperManager.FLAG_LOCK
                WallTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            WallpaperManager.getInstance(context).setBitmap(bmp, null, true, flags)
            bmp.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }
}
