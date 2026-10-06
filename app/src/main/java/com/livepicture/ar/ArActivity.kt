package com.livepicture.ar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.UnavailableException
import com.livepicture.ar.cv.CvTrackActivity
import android.content.Intent
import com.livepicture.ar.rendering.BackgroundRenderer
import com.livepicture.ar.rendering.VideoQuadRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/**
 * صفحهٔ واقعیت افزوده: تصویر دوربین را نشان می‌دهد، تابلوهای ثبت‌شده را با ARCore
 * Augmented Images پیدا و ردیابی می‌کند و ویدیوی هر تابلو را دقیقاً روی آن پخش می‌کند.
 */
class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var muteButton: MaterialButton
    private lateinit var displayRotationHelper: DisplayRotationHelper

    private var session: Session? = null
    private var installRequested = false
    private var permissionDenied = false

    private val background = BackgroundRenderer()
    private val videoRenderer = VideoQuadRenderer()
    private var cameraTextureSet = false

    /** ایندکس تصویر در پایگاه ARCore → تابلو */
    @Volatile private var indexToTarget: Map<Int, ArTarget> = emptyMap()
    @Volatile private var targetsLoaded = false
    private val pendingConfig = AtomicReference<Config?>(null)

    /** پخش‌کننده‌های فعال؛ کلید = ایندکس تصویر */
    private val players = ConcurrentHashMap<Int, VideoPlayback>()
    @Volatile private var muted = false

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private var lastStatus: String? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            permissionDenied = true
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surfaceView)
        statusText = findViewById(R.id.statusText)
        muteButton = findViewById(R.id.btnMute)
        displayRotationHelper = DisplayRotationHelper(this)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)

        muteButton.setOnClickListener {
            muted = !muted
            muteButton.setText(if (muted) R.string.unmute else R.string.mute)
            players.values.forEach { it.setMuted(muted) }
        }
        setStatus(getString(R.string.status_loading))
    }

    override fun onResume() {
        super.onResume()
        if (permissionDenied) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(Manifest.permission.CAMERA)
            return
        }

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                    null -> return
                }
                val s = Session(this)
                s.configure(baseConfig(s))
                session = s
                loadTargets(s)
            } catch (e: UnavailableException) {
                // گوشی ARCore ندارد یا کاربر نصبش نکرد → حالت سازگار
                Log.w(TAG, "ARCore unavailable, switching to OpenCV mode", e)
                startActivity(Intent(this, CvTrackActivity::class.java))
                finish()
                return
            } catch (e: Exception) {
                Log.e(TAG, "ARCore session failed", e)
                setStatus(getString(R.string.arcore_error, e.javaClass.simpleName))
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            setStatus(getString(R.string.camera_unavailable))
            session = null
            return
        }
        surfaceView.onResume()
        displayRotationHelper.onResume()
    }

    override fun onPause() {
        super.onPause()
        session?.let {
            displayRotationHelper.onPause()
            surfaceView.onPause()   // تا توقف کامل ترد GL صبر می‌کند
            it.pause()
        }
        players.values.forEach { it.pause() }
    }

    override fun onDestroy() {
        players.values.forEach { it.release(deleteTexture = false) }
        players.clear()
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun baseConfig(s: Session) = Config(s).apply {
        focusMode = Config.FocusMode.AUTO
        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
    }

    /** ساخت پایگاه تصاویر ARCore در پس‌زمینه (ممکن است چند ثانیه طول بکشد). */
    private fun loadTargets(s: Session) {
        lifecycleScope.launch {
            val skipped = mutableListOf<String>()
            val result = withContext(Dispatchers.Default) {
                val db = AugmentedImageDatabase(s)
                val map = HashMap<Int, ArTarget>()
                for (t in listOf(BundledTarget.get(this@ArActivity))) {
                    val bmp = BitmapFactory.decodeFile(t.imageFile.absolutePath) ?: continue
                    try {
                        val index = if (t.widthMeters > 0f) db.addImage(t.id, bmp, t.widthMeters)
                                    else db.addImage(t.id, bmp)
                        map[index] = t
                    } catch (e: ImageInsufficientQualityException) {
                        skipped += t.name
                    } catch (e: Exception) {
                        Log.w(TAG, "addImage failed for ${t.name}", e)
                        skipped += t.name
                    } finally {
                        bmp.recycle()
                    }
                }
                val cfg = baseConfig(s).apply { augmentedImageDatabase = db }
                map to cfg
            }
            indexToTarget = result.first
            pendingConfig.set(result.second)   // روی ترد GL اعمال می‌شود
            targetsLoaded = true
            if (skipped.isNotEmpty()) {
                Toast.makeText(
                    this@ArActivity,
                    getString(R.string.status_skipped, skipped.joinToString("، ")),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------------------------------------------------------- GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // زمینهٔ GL تازه است؛ بافت‌های قبلی دیگر معتبر نیستند
        players.values.forEach { it.release(deleteTexture = false) }
        players.clear()
        background.createOnGlThread()
        videoRenderer.createOnGlThread()
        cameraTextureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return

        pendingConfig.getAndSet(null)?.let { s.configure(it) }
        if (!cameraTextureSet) {
            s.setCameraTextureName(background.textureId)
            cameraTextureSet = true
        }
        displayRotationHelper.updateSessionIfNeeded(s)

        try {
            val frame = s.update()
            val camera = frame.camera
            background.draw(frame)

            if (camera.trackingState != TrackingState.TRACKING) {
                pauseAllExcept(emptySet())
                setStatus(getString(if (targetsLoaded) R.string.status_tracking_lost else R.string.status_loading))
                return
            }

            camera.getProjectionMatrix(projMatrix, 0, 0.02f, 100f)
            camera.getViewMatrix(viewMatrix, 0)

            val visible = HashSet<Int>()
            var playingName: String? = null
            val now = System.currentTimeMillis()

            for (image in s.getAllTrackables(AugmentedImage::class.java)) {
                if (image.trackingState != TrackingState.TRACKING) continue
                // فقط وقتی تصویر واقعاً در قاب دوربین است پخش می‌کنیم
                if (image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) continue
                val target = indexToTarget[image.index] ?: continue

                visible += image.index
                val playback = players.getOrPut(image.index) { VideoPlayback(target.videoFile, muted) }
                playback.lastSeenMs = now
                playback.play()

                if (playback.updateTexture()) {
                    playback.alpha = min(1f, playback.alpha + 0.08f)
                    image.centerPose.toMatrix(modelMatrix, 0)
                    videoRenderer.draw(
                        modelMatrix, viewMatrix, projMatrix,
                        image.extentX, image.extentZ,
                        playback.stMatrix, playback.textureId, playback.alpha
                    )
                }
                playingName = target.name
            }

            pauseAllExcept(visible)
            releaseStalePlayers(now, visible)

            setStatus(
                when {
                    !targetsLoaded -> getString(R.string.status_loading)
                    playingName != null -> ""   // هنگام پخش، پیام پنهان می‌شود
                    else -> getString(R.string.status_point)
                }
            )
        } catch (e: CameraNotAvailableException) {
            setStatus(getString(R.string.camera_unavailable))
        } catch (t: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun pauseAllExcept(visible: Set<Int>) {
        for ((index, p) in players) {
            if (index !in visible) {
                p.pause()
                p.alpha = 0f
            }
        }
    }

    /** برای صرفه‌جویی حافظه، پخش‌کننده‌هایی که مدتی دیده نشده‌اند آزاد می‌شوند. */
    private fun releaseStalePlayers(now: Long, visible: Set<Int>) {
        val it = players.entries.iterator()
        while (it.hasNext()) {
            val (index, p) = it.next()
            val tooMany = players.size > MAX_PLAYERS
            if (index !in visible && (now - p.lastSeenMs > STALE_MS || tooMany)) {
                p.release(deleteTexture = true)
                it.remove()
            }
        }
    }

    private fun setStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        runOnUiThread {
            statusText.text = text
            statusText.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    companion object {
        private const val TAG = "LivePictureAR"
        private const val MAX_PLAYERS = 3
        private const val STALE_MS = 30_000L
    }
}
