package com.livepicture.ar.cv

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.livepicture.ar.ArTarget
import com.livepicture.ar.R
import com.livepicture.ar.BundledTarget
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * حالت سازگار (بدون ARCore): CameraX تصویر را می‌دهد، PlanarTracker تابلو را پیدا و ردیابی می‌کند،
 * و ویدیو در یک TextureView با تبدیل پرسپکتیو دقیقاً روی چهار گوشهٔ تابلو قرار می‌گیرد.
 */
class CvTrackActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var videoView: TextureView
    private lateinit var statusText: TextView
    private lateinit var muteButton: MaterialButton

    private var controller: LifecycleCameraController? = null
    private lateinit var analysisExecutor: ExecutorService

    // --- فقط روی ترد آنالیز ---
    private var tracker: PlanarTracker? = null
    private var frameBytes = ByteArray(0)
    private var fullGray: Mat? = null
    private val smallGray = Mat()
    @Volatile private var sensorToView: Matrix? = null

    // --- فقط روی ترد اصلی ---
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var currentTarget: ArTarget? = null
    private var prepared = false
    private var wantPlay = false
    private var muted = false
    private var lastSeenMs = 0L
    private var smoothed: FloatArray? = null
    private val viewTransform = Matrix()
    private var lastStatus: String? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cv)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        videoView = findViewById(R.id.videoView)
        statusText = findViewById(R.id.statusText)
        muteButton = findViewById(R.id.btnMute)
        videoView.alpha = 0f
        videoView.isOpaque = false

        muteButton.setOnClickListener {
            muted = !muted
            muteButton.setText(if (muted) R.string.unmute else R.string.mute)
            applyVolume()
        }

        videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                player?.setSurface(surface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setSurface(null)
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        analysisExecutor = Executors.newSingleThreadExecutor()
        setStatus(getString(R.string.status_loading))

        if (!OpenCVLoader.initLocal()) {
            setStatus("OpenCV load failed")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val c = LifecycleCameraController(this)
        c.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        c.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        c.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        c.setImageAnalysisAnalyzer(analysisExecutor, Analyzer())
        c.bindToLifecycle(this)
        previewView.controller = c
        controller = c
    }

    private inner class Analyzer : ImageAnalysis.Analyzer {
        override fun getTargetCoordinateSystem(): Int = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED

        override fun updateTransform(matrix: Matrix?) {
            sensorToView = matrix?.let { Matrix(it) }
        }

        override fun analyze(image: ImageProxy) {
            try {
                analyzeFrame(image)
            } catch (t: Throwable) {
                Log.e(TAG, "analyze failed", t)
            } finally {
                image.close()
            }
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        val t = tracker ?: PlanarTracker(listOf(BundledTarget.get(this))).also {
            tracker = it
            val skipped = it.skipped.toList()
            runOnUiThread {
                if (skipped.isNotEmpty()) {
                    Toast.makeText(this, getString(R.string.status_skipped, skipped.joinToString("، ")), Toast.LENGTH_LONG).show()
                }
                if (!it.hasTargets) setStatus(getString(R.string.no_targets))
            }
        }
        val s2v = sensorToView ?: return

        // صفحهٔ Y (روشنایی) = تصویر خاکستری
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        if (frameBytes.size != w * h) frameBytes = ByteArray(w * h)
        if (rowStride == w) {
            buf.rewind(); buf.get(frameBytes, 0, w * h)
        } else {
            for (r in 0 until h) {
                buf.position(r * rowStride)
                buf.get(frameBytes, r * w, w)
            }
        }
        val full = fullGray?.takeIf { it.rows() == h && it.cols() == w }
            ?: Mat(h, w, CvType.CV_8UC1).also { fullGray?.release(); fullGray = it }
        full.put(0, 0, frameBytes)

        val scale = min(1.0, PROCESS_MAX_DIM / max(w, h).toDouble())
        Imgproc.resize(full, smallGray, org.opencv.core.Size(), scale, scale, Imgproc.INTER_AREA)

        val result = t.process(smallGray)

        if (result == null) {
            runOnUiThread { onTrack(null, null) }
            return
        }

        // مختصات تصویر کوچک ← بافر دوربین ← نمای صفحه
        val pts = FloatArray(8)
        for (i in 0 until 4) {
            pts[i * 2] = (result.corners[i].x / scale).toFloat()
            pts[i * 2 + 1] = (result.corners[i].y / scale).toFloat()
        }
        val bufferToView = Matrix()
        Matrix(image.imageInfo.sensorToBufferTransformMatrix).invert(bufferToView)
        bufferToView.postConcat(s2v)
        bufferToView.mapPoints(pts)

        val target = result.target
        runOnUiThread { onTrack(target, pts) }
    }

    // ----------------------------------------------------------- ترد اصلی

    private fun onTrack(target: ArTarget?, corners: FloatArray?) {
        val now = SystemClock.uptimeMillis()
        if (target != null && corners != null) {
            val recentlySeen = now - lastSeenMs < LOST_GRACE_MS
            lastSeenMs = now
            if (currentTarget?.id != target.id) {
                switchVideo(target)
                smoothed = null
            }
            val s = smoothed
            smoothed = if (s == null || !recentlySeen) corners.copyOf()
            else FloatArray(8) { i -> s[i] + SMOOTHING * (corners[i] - s[i]) }

            applyCorners(smoothed!!)
            videoView.alpha = min(1f, videoView.alpha + 0.2f)
            play()
            setStatus("")   // هنگام پخش، پیام پنهان می‌شود
        } else if (now - lastSeenMs > LOST_GRACE_MS) {
            videoView.alpha = 0f
            pause()
            setStatus(getString(R.string.status_point))
        }
    }

    private fun applyCorners(c: FloatArray) {
        val w = videoView.width.toFloat()
        val h = videoView.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        if (viewTransform.setPolyToPoly(src, 0, c, 0, 4)) {
            videoView.setTransform(viewTransform)
            videoView.invalidate()
        }
    }

    private fun switchVideo(target: ArTarget) {
        player?.release()
        prepared = false
        currentTarget = target
        player = MediaPlayer().apply {
            try {
                setDataSource(target.videoFile.absolutePath)
                surface?.let { setSurface(it) }
                isLooping = true
                setOnPreparedListener {
                    prepared = true
                    applyVolume()
                    if (wantPlay) it.start()
                }
                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "video failed", e)
            }
        }
    }

    private fun play() {
        wantPlay = true
        val p = player ?: return
        if (prepared && !p.isPlaying) p.start()
    }

    private fun pause() {
        wantPlay = false
        val p = player ?: return
        if (prepared && p.isPlaying) p.pause()
    }

    private fun applyVolume() {
        val v = if (muted) 0f else 1f
        if (prepared) player?.setVolume(v, v)
    }

    private fun setStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        statusText.text = text
        statusText.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        analysisExecutor.execute {
            tracker?.release()
            fullGray?.release()
            smallGray.release()
        }
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "LivePictureCV"
        private const val PROCESS_MAX_DIM = 720.0
        private const val LOST_GRACE_MS = 350L
        private const val SMOOTHING = 0.55f
    }
}
