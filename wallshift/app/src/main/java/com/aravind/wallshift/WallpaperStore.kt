package com.aravind.wallshift

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Wallpapers are copied into the app's private storage on import, so they keep
 * working even if the originals are moved or deleted from the gallery.
 */
object WallpaperStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "wallpapers").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    /** Copies the picked image in. Returns true on success. */
    fun import(context: Context, uri: Uri, seq: Int): Boolean = try {
        val out = File(dir(context), "%d_%03d.img".format(System.currentTimeMillis(), seq))
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } != null
    } catch (e: Exception) {
        false
    }

    fun delete(file: File) {
        file.delete()
    }
}
