package io.heckel.ntfy.msg

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton

/**
 * A large red "Stop speaking" button over every screen of the app while infinity speaks,
 * so the voice can be stopped with one tap whenever the app is open (no shade, no
 * notification that may have been dismissed).
 */
object SpeechStopOverlay : Application.ActivityLifecycleCallbacks {
    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    private const val TAG = "infinity-stop-speaking"

    // Added once the screen has its layout (setContentView clears the content frame).
    override fun onActivityStarted(activity: Activity) {
        val owner = activity as? LifecycleOwner ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<android.view.View>(TAG) != null) return
        val density = activity.resources.displayMetrics.density
        val button = MaterialButton(activity).apply {
            tag = TAG
            text = "Stop speaking"
            textSize = 20f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
            cornerRadius = (32 * density).toInt()
            minHeight = (72 * density).toInt()
            setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
            elevation = 24 * density
            visibility = if (SpeechPlayer.speaking.value == true) ViewGroup.VISIBLE else ViewGroup.GONE
            setOnClickListener { SpeechPlayer.stopCurrent(activity.applicationContext) }
        }
        root.addView(button, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = (96 * density).toInt() })
        SpeechPlayer.speaking.observe(owner) { on ->
            button.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE
            if (on) button.bringToFront()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
