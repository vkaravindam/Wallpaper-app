package com.aravind.wallshift

import android.content.Context
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

    /** Picks the next wallpaper according to the order setting and remembers it. */
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

    fun applyFile(context: Context, file: File): Boolean {
        val prefs = Prefs(context)
        val ok = WallpaperSetter.apply(context, file, prefs.target)
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
