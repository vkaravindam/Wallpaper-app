package com.aravind.wallshift

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object Rotator {
    private const val WORK_NAME = "wallshift_rotation"

    /** True when WallShift's live wallpaper is the current home screen wallpaper. */
    fun isLiveActive(context: Context): Boolean = try {
        WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
    } catch (e: Exception) {
        false
    }

    /** Opens the system screen that applies WallShift's live wallpaper. */
    fun liveWallpaperIntent(context: Context): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, LiveWallpaperService::class.java)
        )

    /** Picks the next wallpaper according to the order setting. */
    private fun pickNext(context: Context, prefs: Prefs): File? {
        val files = WallpaperStore.list(context)
        if (files.isEmpty()) return null
        val index = if (prefs.shuffle && files.size > 1) {
            val lastIdx = files.indexOfFirst { it.name == prefs.lastFileName }
            var i: Int
            do { i = Random.nextInt(files.size) } while (i == lastIdx)
            i
        } else {
            (prefs.lastIndex + 1).mod(files.size)
        }
        prefs.lastIndex = index
        return files[index]
    }

    /** Applies the next wallpaper now. Returns false if none are available or setting failed. */
    fun changeNow(context: Context): Boolean {
        val prefs = Prefs(context)
        val file = pickNext(context, prefs) ?: return false
        return applyFile(context, file)
    }

    /**
     * Applies [file] to the screens chosen in settings.
     * - Home with the live wallpaper active: the live wallpaper switches to it (animations
     *   play, wide images pan with home pages).
     * - Home without it: a normal static wallpaper (still frame for GIFs/videos).
     * - Lock: a static image, except "Both" with the live wallpaper active, where the lock
     *   screen shows the live wallpaper too.
     */
    fun applyFile(context: Context, file: File): Boolean {
        val prefs = Prefs(context)
        val target = prefs.target
        val live = isLiveActive(context)
        val wm = WallpaperManager.getInstance(context)
        var ok = true

        if (target != WallTarget.LOCK) {
            prefs.homeFileName = file.name
            if (!live) {
                ok = WallpaperSetter.setStatic(context, file, WallpaperManager.FLAG_SYSTEM, prefs.parallax)
            }
        }
        if (target != WallTarget.HOME) {
            if (live && target == WallTarget.BOTH) {
                // Remove any separate lock image so the lock screen mirrors the live wallpaper.
                try {
                    if (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0) wm.clear(WallpaperManager.FLAG_LOCK)
                } catch (_: Exception) {}
            } else {
                ok = WallpaperSetter.setStatic(context, file, WallpaperManager.FLAG_LOCK, false) && ok
            }
            prefs.lockFileName = file.name
        }
        if (ok) {
            prefs.lastFileName = file.name
            prefs.lastChangedAt = System.currentTimeMillis()
        }
        return ok
    }

    /** (Re)starts or stops the periodic job based on current settings. */
    fun reschedule(context: Context) {
        val prefs = Prefs(context)
        val wm = WorkManager.getInstance(context)
        if (!prefs.enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            prefs.nextChangeAt = 0L
            return
        }
        val minutes = prefs.intervalMinutes.toLong().coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<RotationWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request
        )
        prefs.nextChangeAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes)
    }
}

/** Background job run by WorkManager on each interval. Survives reboots automatically. */
class RotationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.enabled) return Result.success()
        if (WallpaperStore.list(applicationContext).isEmpty()) return Result.success()
        val ok = Rotator.changeNow(applicationContext)
        prefs.nextChangeAt = System.currentTimeMillis() +
            TimeUnit.MINUTES.toMillis(prefs.intervalMinutes.toLong())
        return if (ok) Result.success() else Result.retry()
    }
}
