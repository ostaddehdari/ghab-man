package com.livepicture.ar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.google.ar.core.ArCoreApk
import com.livepicture.ar.cv.CvTrackActivity

/**
 * نقطهٔ شروع بدون رابط کاربری: بلافاصله دوربین را باز می‌کند.
 * اگر گوشی ARCore داشته باشد از ARCore و در غیر این صورت از حالت سازگار (OpenCV) استفاده می‌شود.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arSupported = try {
            !ArCoreApk.getInstance().checkAvailability(this).isUnsupported
        } catch (e: Exception) {
            false
        }
        val screen = if (arSupported) ArActivity::class.java else CvTrackActivity::class.java
        startActivity(Intent(this, screen))
        finish()
    }
}
