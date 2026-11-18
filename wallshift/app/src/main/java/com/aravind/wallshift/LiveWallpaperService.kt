package com.aravind.wallshift

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * WallShift's own live wallpaper. It shows whichever file the rotation picked for the
 * home screen, pans wide images as you swipe between home screen pages, and plays
 * GIFs, animated WebPs and videos (muted, looping).
 */
class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WallEngine()

    inner class WallEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val thread = HandlerThread("wallshift-render").apply { start() }
        private val gl = Handler(thread.looper)
        private val prefs = Prefs(applicationContext)

        // Everything below is only touched on the render thread.
        private var renderer: GlRenderer? = null
        private var sw = 0
        private var sh = 0
        private var pageOffset = 0.5f
        private var visibleNow = false
        private var loadedName: String? = null
        private var renderPending = false

        private var hasContent = false
        private var contentW = 0
        private var contentH = 0

        private var anim: AnimatedImageDrawable? = null
        private var animFrame: Bitmap? = null

        private var player: MediaPlayer? = null
        private var playerPrepared = false
        private var surfaceTexture: SurfaceTexture? = null
        private var videoSurface: Surface? = null
        private var videoFramePending = false
        private var videoHasFrame = false
        private val stMatrix = FloatArray(16)

        private val animCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = requestRender()
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                gl.postAtTime(what, who, `when`)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                gl.removeCallbacks(what, who)
            }
        }

        // ---------- engine callbacks (main thread) ----------

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)
            setOffsetNotificationsEnabled(true)
            prefs.register(this)
        }

        override fun onDestroy() {
            prefs.unregister(this)
            runOnGlAndWait { releaseMedia(); renderer?.release(); renderer = null }
            thread.quitSafely()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            runOnGlAndWait {
                renderer = try { GlRenderer(holder.surface) } catch (e: Exception) { null }
                loadedName = null
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            runOnGlAndWait {
                val sizeChanged = width != sw || height != sh
                sw = width
                sh = height
                load(force = sizeChanged)
                render()
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            runOnGlAndWait { render() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            runOnGlAndWait {
                releaseMedia()
                renderer?.release()
                renderer = null
                loadedName = null
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            gl.post {
                visibleNow = visible
                if (visible) {
                    load(force = false)
                    if (playerPrepared) player?.start()
                    anim?.start()
                    requestRender()
                } else {
                    if (playerPrepared) player?.pause()
                    anim?.stop()
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int,
        ) {
            gl.post {
                pageOffset = xOffset
                requestRender()
            }
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Prefs.KEY_HOME_FILE -> gl.post { load(force = false); requestRender() }
                Prefs.KEY_PARALLAX -> gl.post { requestRender() }
            }
        }

        // ---------- render thread ----------

        private fun runOnGlAndWait(block: () -> Unit) {
            if (Looper.myLooper() == thread.looper) { block(); return }
            val latch = CountDownLatch(1)
            val posted = gl.post { try { block() } finally { latch.countDown() } }
            if (posted) latch.await(3, TimeUnit.SECONDS)
        }

        private fun currentFile(): File? {
            val files = WallpaperStore.list(applicationContext)
            val name = prefs.homeFileName
            return files.firstOrNull { it.name == name } ?: files.firstOrNull()
        }

        private fun load(force: Boolean) {
            val r = renderer ?: return
            if (sw == 0 || sh == 0) return
            val file = currentFile()
            if (!force && file?.name == loadedName && (hasContent || file == null)) return
            releaseMedia()
            loadedName = file?.name
            if (file == null) return
            try {
                when {
                    Media.isVideo(file) -> startVideo(r, file)
                    Media.mayBeAnimated(file) && startAnimated(file) -> Unit
                    else -> loadStill(r, file)
                }
            } catch (e: Exception) {
                releaseMedia()
            }
        }

        private fun loadStill(r: GlRenderer, file: File) {
            val src = Media.decodeSampled(file, sw, sh) ?: return
            val maxW = min(r.maxTextureSize, sw * 4)
            val bmp = Media.scaleToCover(src, sw, sh, maxW)
            if (bmp !== src) src.recycle()
            r.uploadBitmap(bmp)
            contentW = bmp.width
            contentH = bmp.height
            bmp.recycle()
            hasContent = true
        }

        private fun startAnimated(file: File): Boolean {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val h = info.size.height
                if (h > sh && h > 0) {
                    val s = sh.toFloat() / h
                    decoder.setTargetSize(
                        (info.size.width * s).roundToInt().coerceAtLeast(1),
                        (h * s).roundToInt().coerceAtLeast(1)
                    )
                }
            }
            if (drawable !is AnimatedImageDrawable) return false // a still WebP

            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            animFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.callback = animCallback
            anim = drawable
            contentW = w
            contentH = h
            hasContent = true
            if (visibleNow) drawable.start()
            return true
        }

        private fun startVideo(r: GlRenderer, file: File) {
            val (vw, vh) = Media.videoDisplaySize(file) ?: (sw to sh)
            contentW = vw
            contentH = vh

            val st = SurfaceTexture(r.textureOes)
            st.setOnFrameAvailableListener({
                videoFramePending = true
                requestRender()
            }, gl)
            val surf = Surface(st)
            surfaceTexture = st
            videoSurface = surf

            val mp = MediaPlayer()
            player = mp
            mp.setSurface(surf)
            mp.setDataSource(file.path)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                playerPrepared = true
                if (visibleNow) it.start()
            }
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepareAsync()
            hasContent = true
        }

        private fun releaseMedia() {
            anim?.let {
                it.stop()
                it.callback = null
                gl.removeCallbacksAndMessages(it)
            }
            anim = null
            animFrame?.recycle()
            animFrame = null

            player?.let {
                try { it.stop() } catch (_: Exception) {}
                it.release()
            }
            player = null
            playerPrepared = false
            videoSurface?.release()
            videoSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
            videoFramePending = false
            videoHasFrame = false

            hasContent = false
        }

        private fun requestRender() {
            if (Looper.myLooper() != thread.looper) {
                gl.post { requestRender() }
                return
            }
            if (renderPending) return
            renderPending = true
            gl.post {
                renderPending = false
                render()
            }
        }

        private fun render() {
            val r = renderer ?: return
            if (sw == 0 || sh == 0) return
            val parallax = prefs.parallax
            try {
                val st = surfaceTexture
                val a = anim
                val frame = animFrame
                when {
                    !hasContent -> r.drawBlank(sw, sh)
                    st != null -> {
                        if (videoFramePending) {
                            videoFramePending = false
                            st.updateTexImage()
                            st.getTransformMatrix(stMatrix)
                            videoHasFrame = true
                        }
                        if (videoHasFrame) r.draw(contentW, contentH, sw, sh, pageOffset, parallax, stMatrix)
                        else r.drawBlank(sw, sh)
                    }
                    a != null && frame != null -> {
                        frame.eraseColor(Color.TRANSPARENT)
                        a.draw(Canvas(frame))
                        r.uploadBitmap(frame)
                        r.draw(contentW, contentH, sw, sh, pageOffset, parallax, null)
                    }
                    else -> r.draw(contentW, contentH, sw, sh, pageOffset, parallax, null)
                }
            } catch (_: Exception) {
            }
        }
    }
}
