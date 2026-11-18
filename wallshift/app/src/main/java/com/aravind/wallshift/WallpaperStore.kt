package com.aravind.wallshift

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Wallpapers are copied into the app's private storage on import, so they keep
 * working even if the originals are moved or deleted from the gallery.
 * App updates keep this storage; only uninstalling the app clears it.
 */
object WallpaperStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "wallpapers").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.name } ?: emptyList()

    /** Copies the picked image or video in. Returns true on success. */
    fun import(context: Context, uri: Uri, seq: Int): Boolean {
        val ext = Media.extensionFor(context.contentResolver.getType(uri))
        val name = "%d_%03d.%s".format(System.currentTimeMillis(), seq, ext)
        val tmp = File(dir(context), "$name.part")
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } != null
            copied && tmp.renameTo(File(dir(context), name))
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    fun delete(file: File) {
        file.delete()
    }
}
