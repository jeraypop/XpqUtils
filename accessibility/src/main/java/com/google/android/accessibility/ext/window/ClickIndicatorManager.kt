package com.google.android.accessibility.ext.window

import android.app.Application
import android.content.Context
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
import java.util.concurrent.atomic.AtomicBoolean

object ClickIndicatorManager {

    private const val TAG = "ClickIndicatorMgr"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 单个红点显示时长 */
    private const val DURATION_MS = 50L

    /** 红点直径 */
    const val DIAMETER_DP = 10

    /** ⭐ 核心：显示最小间隔（限流，防止高频 addView） */
    private const val MIN_INTERVAL_MS = 30L

    @Volatile
    private var lastShowTime = 0L

    /**
     * 显示点击红点（稳定版）
     */
    fun show(context: Context, x: Int, y: Int, duration: Long = DURATION_MS) {
        val now = System.currentTimeMillis()
        if (now - lastShowTime < MIN_INTERVAL_MS) {
            return
        }
        lastShowTime = now

        val appCtx = context.applicationContext

        mainHandler.post {
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                Log.w(TAG, "WindowManager is null")
                return@post
            }

            val sizePx = pxFromDp(appCtx, DIAMETER_DP)

            val removed = AtomicBoolean(false)

            fun safeRemove(view: View) {
                if (removed.compareAndSet(false, true)) {
                    try {
                        wm.removeViewImmediate(view)
                    } catch (_: Throwable) {
                        try {
                            wm.removeView(view)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            val circle = ImageView(appCtx).apply {
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                setImageDrawable(createCircleDrawable())
                isClickable = false
                isFocusable = false
                alpha = 1f
                ViewCompat.setElevation(this, 100f)
            }

            val container = FrameLayout(appCtx).apply {
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                addView(circle)
                isClickable = false
                isFocusable = false
            }

            val lp = WindowManager.LayoutParams().apply {
                width = sizePx
                height = sizePx
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                flags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                type = chooseWindowType()
                val half = sizePx / 2
                this.x = x - half
                this.y = y - half
            }

            try {
                wm.addView(container, lp)
            } catch (t: Throwable) {
                Log.w(TAG, "addView failed: ${t.message}")
                return@post
            }

            container.animate()
                .alpha(0f)
                .setStartDelay((duration - 20).coerceAtLeast(0))
                .setDuration(20)
                .withEndAction {
                    safeRemove(container)
                }
                .start()

            // 兜底移除（防止动画没走完）
            mainHandler.postDelayed({
                safeRemove(container)
            }, duration + 100)
        }
    }

    private fun chooseWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

     fun createCircleDrawable(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFF0000.toInt())
        }

     fun pxFromDp(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
