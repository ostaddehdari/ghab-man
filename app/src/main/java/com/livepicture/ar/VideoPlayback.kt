package com.livepicture.ar

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.livepicture.ar.rendering.ShaderUtil
import java.io.File

/**
 * پخش ویدیو داخل یک بافت OpenGL. باید روی ترد GL ساخته شود.
 * فریم‌ها از MediaPlayer → SurfaceTexture → بافت OES می‌روند.
 */
class VideoPlayback(file: File, muted: Boolean) {

    val textureId: Int = ShaderUtil.createOesTexture()
    val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** برای ظاهر شدن نرم ویدیو روی تابلو. */
    var alpha = 0f
    var lastSeenMs = System.currentTimeMillis()

    private val surfaceTexture = SurfaceTexture(textureId)
    private val surface = Surface(surfaceTexture)
    private val player = MediaPlayer()
    private val lock = Any()

    @Volatile private var frameAvailable = false
    private var hasFrame = false
    private var prepared = false
    private var wantPlay = false
    private var released = false

    init {
        surfaceTexture.setOnFrameAvailableListener { frameAvailable = true }
        player.setDataSource(file.absolutePath)
        player.setSurface(surface)
        player.isLooping = true
        setMuted(muted)
        player.setOnPreparedListener {
            synchronized(lock) {
                if (released) return@synchronized
                prepared = true
                if (wantPlay) it.start()
            }
        }
        player.prepareAsync()
    }

    fun play() = synchronized(lock) {
        wantPlay = true
        if (prepared && !released && !player.isPlaying) player.start()
    }

    fun pause() = synchronized(lock) {
        wantPlay = false
        if (prepared && !released && player.isPlaying) player.pause()
    }

    fun setMuted(muted: Boolean) = synchronized(lock) {
        if (!released) {
            val v = if (muted) 0f else 1f
            player.setVolume(v, v)
        }
    }

    /** روی ترد GL صدا زده شود. true یعنی حداقل یک فریم برای نمایش داریم. */
    fun updateTexture(): Boolean {
        if (frameAvailable) {
            frameAvailable = false
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            hasFrame = true
        }
        return hasFrame
    }

    /** آزادسازی. اگر روی ترد GL هستید deleteTexture=true بدهید. */
    fun release(deleteTexture: Boolean) {
        synchronized(lock) {
            if (released) return
            released = true
            player.release()
        }
        surface.release()
        surfaceTexture.release()
        if (deleteTexture) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }
}
