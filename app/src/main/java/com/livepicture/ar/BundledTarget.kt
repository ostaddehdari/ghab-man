package com.livepicture.ar

import android.content.Context
import java.io.File

/** یک «قاب»: تصویر هدف + ویدیویی که روی آن پخش می‌شود. */
data class ArTarget(
    val id: String,
    val name: String,
    /** عرض فیزیکی عکس چاپ‌شده به متر؛ صفر یعنی ARCore خودش تخمین بزند. */
    val widthMeters: Float,
    val dir: File,
) {
    val imageFile: File get() = File(dir, "target.jpg")
    val videoFile: File get() = File(dir, "video.mp4")
}

/**
 * عکس و فیلم داخل خود برنامه (پوشهٔ assets) قرار دارند.
 * بار اول به حافظهٔ داخلی کپی می‌شوند تا MediaPlayer و ARCore مستقیم از فایل بخوانند.
 * برای عوض کردن عکس/فیلم کافی است فایل‌های app/src/main/assets را جایگزین و versionCode را زیاد کنید.
 */
object BundledTarget {

    private val ASSETS = listOf("target.jpg", "video.mp4")

    @Volatile private var cached: ArTarget? = null

    @Synchronized
    fun get(context: Context): ArTarget {
        cached?.let { return it }
        val app = context.applicationContext
        val dir = File(app.filesDir, "bundled").apply { mkdirs() }
        val version = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
        val stamp = File(dir, "version")

        val upToDate = stamp.exists() && stamp.readText() == version && ASSETS.all { File(dir, it).length() > 0 }
        if (!upToDate) {
            for (name in ASSETS) {
                val tmp = File(dir, "$name.tmp")
                app.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(File(dir, name))
            }
            stamp.writeText(version)
        }
        return ArTarget(
            id = "my_frame",
            name = app.getString(R.string.app_name),
            widthMeters = 0f,
            dir = dir,
        ).also { cached = it }
    }
}
