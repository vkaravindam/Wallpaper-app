package com.aravind.wallshift

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
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
    var currentName: String? = null

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun submit(files: List<File>, current: String?) {
        items = files
        currentName = current
        notifyDataSetChanged()
    }

    class VH(val b: ItemWallpaperBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemWallpaperBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // 3 columns, phone-shaped (9:16) tiles
        val tileW = parent.measuredWidth / 3
        if (tileW > 0) b.root.layoutParams.height = tileW * 16 / 9
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = items[position]
        val img = holder.b.thumb
        img.tag = file.path
        holder.b.currentBadge.visibility =
            if (file.name == currentName) android.view.View.VISIBLE else android.view.View.GONE
        holder.b.root.setOnClickListener { onClick(file) }

        cache.get(file.path)?.let { img.setImageBitmap(it); return }
        img.setImageDrawable(null)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeThumb(file) } ?: return@launch
            cache.put(file.path, bmp)
            if (img.tag == file.path) img.setImageBitmap(bmp)
        }
    }

    private fun decodeThumb(file: File, target: Int = 360): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
