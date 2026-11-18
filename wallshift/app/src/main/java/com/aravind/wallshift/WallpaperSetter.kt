package com.aravind.wallshift

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Sets ordinary (static) system wallpapers. Used when the live wallpaper isn't active, and for the lock screen. */
object WallpaperSetter {

    /** Physical screen size in portrait orientation (width < height). */
    fun screenSize(context: Context): Pair<Int, Int> {
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

    /**
     * Sets [file] (a still frame for GIFs and videos) on the screen given by [which]
     * (WallpaperManager.FLAG_SYSTEM or FLAG_LOCK). With [wide], images wider than the
     * screen keep their extra width so launchers that scroll wallpapers can pan them.
     */
    fun setStatic(context: Context, file: File, which: Int, wide: Boolean): Boolean {
        return try {
            val (w, h) = screenSize(context)
            val src = Media.stillFrame(file, w, h) ?: return false
            val maxW = if (wide) w * 3 else w
            val bmp = Media.scaleToCover(src, w, h, maxW)
            if (bmp !== src) src.recycle()

            val wm = WallpaperManager.getInstance(context)
            if (which == WallpaperManager.FLAG_SYSTEM) {
                try { wm.suggestDesiredDimensions(bmp.width, h) } catch (_: Exception) {}
            }
            wm.setBitmap(bmp, null, true, which)
            bmp.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }
}
