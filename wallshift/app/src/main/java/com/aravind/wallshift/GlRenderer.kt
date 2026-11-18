package com.aravind.wallshift

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Minimal OpenGL ES 2 renderer for the live wallpaper. Draws either a normal texture
 * (still images, GIF frames) or an external texture (video frames), scaled to cover
 * the screen and panned horizontally by the launcher's page offset.
 * Must be created, used and released on one thread.
 */
class GlRenderer(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    private val prog2d: Int
    private val progOes: Int
    private val quad: FloatBuffer

    val texture2d: Int
    val textureOes: Int
    val maxTextureSize: Int

    private val crop = FloatArray(16)
    private val flip = FloatArray(16)
    private val finalM = FloatArray(16)

    init {
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: error("No EGL config")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        prog2d = program(VS, FS_2D)
        progOes = program(VS, FS_OES)

        // x, y, u, v — standard GL texture space, (0,0) = bottom-left
        val data = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        quad = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        texture2d = ids[0]
        textureOes = ids[1]
        setupTexture(GLES20.GL_TEXTURE_2D, texture2d)
        setupTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureOes)

        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        maxTextureSize = if (max[0] > 0) max[0] else 4096

        // Bitmaps are uploaded top row first, so flip v.
        Matrix.setIdentityM(flip, 0)
        flip[5] = -1f
        flip[13] = 1f
    }

    private fun setupTexture(target: Int, id: Int) {
        GLES20.glBindTexture(target, id)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun uploadBitmap(bmp: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture2d)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    fun drawBlank(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    /**
     * Draws content of size cw×ch covering a sw×sh surface. [xOffset] is the launcher's page
     * position (0 = first page, 1 = last). For video, pass the SurfaceTexture matrix as [stMatrix].
     */
    fun draw(cw: Int, ch: Int, sw: Int, sh: Int, xOffset: Float, parallax: Boolean, stMatrix: FloatArray?) {
        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val s = max(sw.toFloat() / cw, sh.toFloat() / ch)
        val u = (sw / (cw * s)).coerceAtMost(1f)
        val v = (sh / (ch * s)).coerceAtMost(1f)
        val u0 = if (parallax) (1f - u) * xOffset.coerceIn(0f, 1f) else (1f - u) / 2f
        val v0 = (1f - v) / 2f

        Matrix.setIdentityM(crop, 0)
        crop[0] = u
        crop[5] = v
        crop[12] = u0
        crop[13] = v0

        val oes = stMatrix != null
        if (oes) Matrix.multiplyMM(finalM, 0, stMatrix!!, 0, crop, 0)
        else Matrix.multiplyMM(finalM, 0, crop, 0, flip, 0)

        val prog = if (oes) progOes else prog2d
        GLES20.glUseProgram(prog)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUv = GLES20.glGetAttribLocation(prog, "aUv")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uM"), 1, false, finalM, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (oes) GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureOes)
        else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture2d)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        try {
            GLES20.glDeleteTextures(2, intArrayOf(texture2d, textureOes), 0)
            GLES20.glDeleteProgram(prog2d)
            GLES20.glDeleteProgram(progOes)
        } catch (_: Exception) {}
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader error: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Link error: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val VS = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uM;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uM * aUv).xy;
            }
        """
        const val FS_2D = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
        const val FS_OES = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
    }
}
