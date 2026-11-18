package com.aravind.wallshift

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aravind.wallshift.databinding.ItemWallpaperBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WallpaperAdapter(
    private val scope: CoroutineScope,
    private val onClick: (File) -> Unit,
) : RecyclerView.Adapter<WallpaperAdapter.VH>() {

    private var items: List<File> = emptyList()
    private var homeName: String? = null
    private var lockName: String? = null

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun submit(files: List<File>, home: String?, lock: String?) {
        items = files
        homeName = home
        lockName = lock
        notifyDataSetChanged()
    }

    class VH(val b: ItemWallpaperBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWallpaperBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = items[position]
        val img = holder.b.thumb
        img.tag = file.path
        holder.b.root.setOnClickListener { onClick(file) }

        val onHome = file.name == homeName
        val onLock = file.name == lockName
        holder.b.currentBadge.visibility = if (onHome || onLock) View.VISIBLE else View.GONE
        holder.b.currentBadge.text = when {
            onHome && onLock -> "Home + Lock"
            onHome -> "Home"
            else -> "Lock"
        }
        val label = Media.label(file)
        holder.b.typeBadge.visibility = if (label != null) View.VISIBLE else View.GONE
        holder.b.typeBadge.text = label ?: ""

        val cached = cache.get(file.path)
        if (cached != null) {
            img.setImageBitmap(cached)
            return
        }
        img.setImageDrawable(null)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { Media.stillFrame(file, 240, 427) } ?: return@launch
            cache.put(file.path, bmp)
            if (img.tag == file.path) img.setImageBitmap(bmp)
        }
    }
}
