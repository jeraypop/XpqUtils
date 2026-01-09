package com.google.android.accessibility.ext.window



/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/10/20  14:53
 * Description:This is ClickIndicatorManager
 */
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
import kotlin.apply

object ClickIndicatorManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val TAG = "ClickIndicatorMgr"

    // 每个红点显示时长（毫秒）
    private const val DURATION_MS: Long = 50
    // 红点直径
    const val DIAMETER_DP = 10

    /**
     * 每次点击都会创建一个独立的红点
     */
    fun show(context: Context, x: Int, y: Int, duration: Long = DURATION_MS) {
        try {
            mainHandler.post {
                try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    if (wm == null) {
                        Log.w(TAG, "WindowManager null, cannot show indicator")
                        return@post
                    }

                    val sizePx = pxFromDp(context, DIAMETER_DP)

                    // 子 View：红色圆点。直接指定宽高，避免测量问题。
                    val circle = ImageView(context).apply {
                        // 明确大小，避免 WRAP_CONTENT 导致 0 大小
                        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                        // 直接设置 drawable 为不透明红（便于调试）
                        setImageDrawable(createCircleDrawableOpaque())
                        isClickable = false
                        isFocusable = false
                        ViewCompat.setElevation(this, 100f)
                        alpha = 1f
                    }

                    // 容器：固定大小与子 view 一致，确保 addView 后能正确显示
                    val container = FrameLayout(context).apply {
                        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                        addView(circle)
                        visibility = View.VISIBLE
                        // 不拦截触摸
                        isClickable = false
                        isFocusable = false
                    }

                    val type = chooseWindowType()

                    val lp = WindowManager.LayoutParams().apply {
                        width = sizePx
                        height = sizePx
                        format = PixelFormat.TRANSLUCENT
                        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        gravity = Gravity.TOP or Gravity.START
                        this.type = type

                        val half = sizePx / 2
                        this.x = x - half
                        this.y = y - half
                    }

                    try {
                        wm.addView(container, lp)
                        Log.d(TAG, "added indicator at x=${lp.x}, y=${lp.y}, size=$sizePx, type=$type")
                    } catch (addEx: Throwable) {
                        Log.w(TAG, "addView failed: ${addEx.message}")
                        try {
                            // 兼容尝试：如果 first addView 失败，再尝试 weaker flags / other type (慎用)
                            wm.addView(container, lp)
                        } catch (t: Throwable) {
                            Log.w(TAG, "second addView also failed: ${t.message}")
                            return@post
                        }
                    }

                    // 淡出动画（最后 200ms 做动画）
                    container.animate()
                        .alpha(0f)
                        .setStartDelay(duration - 200)
                        .setDuration(200)
                        .withEndAction {
                            try {
                                wm.removeViewImmediate(container)
                                Log.d(TAG, "removed indicator after animation")
                            } catch (t: Throwable) {
                                Log.w(TAG, "removeViewImmediate failed: ${t.message}")
                                try { wm.removeView(container) } catch (_: Throwable) {}
                            }
                        }
                        .start()

                    // 保险移除（防止动画异常）
                    mainHandler.postDelayed({
                        try {
                            wm.removeViewImmediate(container)
                        } catch (t: Throwable) {
                            try { wm.removeView(container) } catch (_: Throwable) {}
                        }
                    }, duration + 300)

                } catch (t: Throwable) {
                    Log.w(TAG, "show internal error: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post failed: ${t.message}")
        }
    }

    private fun chooseWindowType(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // TYPE_ACCESSIBILITY_OVERLAY (API 28) 优先，适用于 AccessibilityService
                if (Build.VERSION.SDK_INT >= 28) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        } catch (_: Throwable) {
            // 兜底
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // 不透明红色用于确认显示（调试用），之后可以改成半透明 createCircleDrawable()
    private fun createCircleDrawableOpaque() =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFF0000.toInt()) // 不透明红色
            setStroke( (2 * ResourcesHolder.density).toInt(), 0x66FF0000.toInt())
        }

    fun pxFromDp(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    // holder to avoid repeated density lookups in drawable stroke
    private object ResourcesHolder {
        val density: Float by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            }; // nop to quiet linter
            // fallback: fetch from mainHandler's Looper context is unavailable here - we'll use 1f
            1f
        }
    }
}

