package com.google.android.accessibility.ext.window

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import com.google.android.accessibility.ext.window.AssistsWindowManager.DIAMETER_DP
import com.google.android.accessibility.ext.window.AssistsWindowManager.pxFromDp
import kotlin.apply

object ClickIndicatorManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val TAG = "ClickIndicatorMgr"
    private const val DURATION_MS = 300L

    private var wm: WindowManager? = null
    private var container: FrameLayout? = null
    private var circle: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var isAdded = false

    fun show(context: Context, x: Int, y: Int, duration: Long = DURATION_MS) {
        mainHandler.post {
            try {
                ensureWindow(context)

                val container = container ?: return@post
                val circle = circle ?: return@post
                val lp = lp ?: return@post
                val wm = wm ?: return@post

                val size = pxFromDp(context, DIAMETER_DP)
                val half = size / 2

                lp.x = x - half
                lp.y = y - half
                wm.updateViewLayout(container, lp)

                circle.animate().cancel()
                container.visibility = View.VISIBLE
                circle.alpha = 1f

                circle.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .start()

            } catch (t: Throwable) {
                Log.e(TAG, "show failed", t)
            }
        }
    }

    private fun ensureWindow(context: Context) {
        if (isAdded) return

        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = pxFromDp(context, DIAMETER_DP)

        circle = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            setImageDrawable(createCircleDrawable())
            isClickable = false
            isFocusable = false
            elevation = 100f
        }

        container = FrameLayout(context).apply {
            addView(circle)
        }

        lp = WindowManager.LayoutParams(
            size,
            size,
            AssistsWindowManager.chooseWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        wm!!.addView(container, lp)
        isAdded = true
    }

    private fun createCircleDrawable() =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.RED)
        }

    fun cleanup() {
        mainHandler.post {
            try {
                if (isAdded) {
                    wm?.removeViewImmediate(container)
                }
            } catch (_: Throwable) {}
            isAdded = false
            wm = null
            container = null
            circle = null
            lp = null
        }
    }
}

