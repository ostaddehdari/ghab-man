package com.livepicture.ar

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session

/** چرخش صفحه و ابعاد نما را به ARCore اطلاع می‌دهد. */
class DisplayRotationHelper(activity: Activity) : DisplayManager.DisplayListener {

    private val displayManager = activity.getSystemService(DisplayManager::class.java)

    @Suppress("DEPRECATION")
    private val display: Display =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display!!
        else activity.getSystemService(WindowManager::class.java).defaultDisplay

    @Volatile private var changed = false
    private var width = 0
    private var height = 0

    fun onResume() = displayManager.registerDisplayListener(this, null)
    fun onPause() = displayManager.unregisterDisplayListener(this)

    fun onSurfaceChanged(w: Int, h: Int) {
        width = w
        height = h
        changed = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (changed) {
            session.setDisplayGeometry(display.rotation, width, height)
            changed = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        changed = true
    }
}
