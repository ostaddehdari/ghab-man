package com.livepicture.ar.cv

import android.graphics.BitmapFactory
import com.livepicture.ar.ArTarget
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * ردیاب تصویر صفحه‌ای بدون ARCore (روی هر گوشی دوربین‌دار).
 * - تشخیص: ویژگی‌های ORB + تطبیق + هوموگرافی RANSAC
 * - ردیابی: جریان نوری Lucas-Kanade بین فریم‌ها (سریع و روان)
 * - تازه‌سازی دوره‌ای نقاط با ORB برای جلوگیری از لغزش
 * همهٔ متدها باید روی یک ترد (ترد آنالیز دوربین) صدا زده شوند.
 */
class PlanarTracker(targets: List<ArTarget>) {

    class Result(val target: ArTarget, val corners: Array<Point>)

    private class Ref(val target: ArTarget, val keypoints: Array<Point>, val desc: Mat, val corners: MatOfPoint2f)
    private class Fit(val refPts: Array<Point>, val curPts: Array<Point>, val corners: Array<Point>)

    private val orbRef = ORB.create(1200)
    private val orbFrame = ORB.create(1000)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
    private val refs: List<Ref>

    /** نام تصاویری که جزئیات کافی برای ردیابی نداشتند. */
    val skipped = mutableListOf<String>()

    private var current: Ref? = null
    private var prevGray: Mat? = null
    private var refPts: Array<Point> = emptyArray()
    private var curPts: Array<Point> = emptyArray()
    private var framesSinceRefresh = 0

    init {
        refs = targets.mapNotNull { buildRef(it) }
    }

    val hasTargets: Boolean get() = refs.isNotEmpty()

    private fun buildRef(t: ArTarget): Ref? {
        val bmp = BitmapFactory.decodeFile(t.imageFile.absolutePath) ?: return null
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        bmp.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val s = REF_MAX_DIM / max(gray.cols(), gray.rows()).toDouble()
        if (s < 1.0) Imgproc.resize(gray, gray, Size(), s, s, Imgproc.INTER_AREA)

        val kp = MatOfKeyPoint()
        val desc = Mat()
        orbRef.detectAndCompute(gray, Mat(), kp, desc)
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        gray.release()
        if (desc.rows() < MIN_REF_FEATURES) {
            skipped += t.name
            kp.release(); desc.release()
            return null
        }
        val pts = kp.toArray().map { it.pt }.toTypedArray()
        kp.release()
        return Ref(t, pts, desc, MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)))
    }

    /** یک فریم خاکستری را پردازش می‌کند؛ گوشه‌های تصویر (بالا-چپ، بالا-راست، پایین-راست، پایین-چپ) را برمی‌گرداند. */
    fun process(gray: Mat): Result? {
        var result: Result? = null
        val cur = current

        if (cur != null) {
            val fit = track(gray, cur)
            if (fit == null) {
                current = null
            } else {
                refPts = fit.refPts
                curPts = fit.curPts
                result = Result(cur.target, fit.corners)
                framesSinceRefresh++
                if (framesSinceRefresh >= REFRESH_EVERY || curPts.size < LOW_POINTS) {
                    framesSinceRefresh = 0
                    detect(gray, cur)?.let { (_, f) ->
                        refPts = f.refPts
                        curPts = f.curPts
                        result = Result(cur.target, f.corners)
                    }
                }
            }
        }

        if (current == null) {
            detect(gray, null)?.let { (ref, f) ->
                current = ref
                refPts = f.refPts
                curPts = f.curPts
                framesSinceRefresh = 0
                result = Result(ref.target, f.corners)
            }
        }

        prevGray?.release()
        prevGray = gray.clone()
        return result
    }

    private fun detect(gray: Mat, only: Ref?): Pair<Ref, Fit>? {
        val kp = MatOfKeyPoint()
        val desc = Mat()
        orbFrame.detectAndCompute(gray, Mat(), kp, desc)
        try {
            if (desc.rows() < MIN_MATCHES) return null
            val framePts = kp.toArray()
            val frameSize = gray.size()
            var best: Pair<Ref, Fit>? = null
            for (ref in only?.let { listOf(it) } ?: refs) {
                val knn = ArrayList<MatOfDMatch>()
                matcher.knnMatch(ref.desc, desc, knn, 2)
                val src = ArrayList<Point>()
                val dst = ArrayList<Point>()
                for (m in knn) {
                    val a = m.toArray()
                    if (a.size == 2 && a[0].distance < RATIO * a[1].distance) {
                        src += ref.keypoints[a[0].queryIdx]
                        dst += framePts[a[0].trainIdx].pt
                    }
                    m.release()
                }
                if (src.size < MIN_MATCHES) continue
                val fit = fitHomography(src, dst, 5.0, ref, frameSize) ?: continue
                if (best == null || fit.curPts.size > best.second.curPts.size) best = ref to fit
            }
            return best
        } finally {
            kp.release()
            desc.release()
        }
    }

    private fun track(gray: Mat, ref: Ref): Fit? {
        val prev = prevGray ?: return null
        if (curPts.size < MIN_INLIERS) return null
        val prevM = MatOfPoint2f(*curPts)
        val nextM = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(prev, gray, prevM, nextM, status, err, Size(21.0, 21.0), 3)
            val st = status.toArray()
            val nx = nextM.toArray()
            val w = gray.cols().toDouble()
            val h = gray.rows().toDouble()
            val src = ArrayList<Point>()
            val dst = ArrayList<Point>()
            for (i in st.indices) {
                val p = nx[i]
                if (st[i].toInt() == 1 && p.x >= 0 && p.y >= 0 && p.x < w && p.y < h) {
                    src += refPts[i]
                    dst += p
                }
            }
            if (src.size < MIN_INLIERS) return null
            return fitHomography(src, dst, 3.0, ref, gray.size())
        } finally {
            prevM.release(); nextM.release(); status.release(); err.release()
        }
    }

    private fun fitHomography(src: List<Point>, dst: List<Point>, threshold: Double, ref: Ref, frame: Size): Fit? {
        val srcM = MatOfPoint2f(*src.toTypedArray())
        val dstM = MatOfPoint2f(*dst.toTypedArray())
        val mask = Mat()
        var hom: Mat? = null
        val projected = MatOfPoint2f()
        try {
            hom = Calib3d.findHomography(srcM, dstM, Calib3d.RANSAC, threshold, mask)
            if (hom == null || hom.empty()) return null
            val flags = ByteArray(mask.total().toInt())
            mask.get(0, 0, flags)
            val inRef = ArrayList<Point>()
            val inCur = ArrayList<Point>()
            for (i in flags.indices) if (flags[i].toInt() != 0) {
                inRef += src[i]; inCur += dst[i]
            }
            if (inRef.size < MIN_INLIERS) return null
            Core.perspectiveTransform(ref.corners, projected, hom)
            val c = projected.toArray()
            if (!isPlausible(c, frame)) return null
            return Fit(inRef.toTypedArray(), inCur.toTypedArray(), c)
        } finally {
            srcM.release(); dstM.release(); mask.release(); hom?.release(); projected.release()
        }
    }

    /** چهارضلعی باید محدب، با اندازهٔ معقول و بدون اضلاع خیلی کوتاه باشد. */
    private fun isPlausible(c: Array<Point>, frame: Size): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]; val d = c[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0 || (sign != 0 && s != sign)) return false
            sign = s
            if (hypot(b.x - a.x, b.y - a.y) < 12.0) return false
        }
        var area = 0.0
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]
            area += a.x * b.y - b.x * a.y
        }
        area = abs(area) / 2
        val frameArea = frame.width * frame.height
        return area > frameArea * 0.01 && area < frameArea * 25
    }

    fun reset() {
        current = null
        prevGray?.release()
        prevGray = null
    }

    fun release() {
        reset()
        refs.forEach { it.desc.release(); it.corners.release() }
    }

    companion object {
        private const val REF_MAX_DIM = 520.0
        private const val MIN_REF_FEATURES = 60
        private const val RATIO = 0.75
        private const val MIN_MATCHES = 18
        private const val MIN_INLIERS = 12
        private const val LOW_POINTS = 40
        private const val REFRESH_EVERY = 20
    }
}
