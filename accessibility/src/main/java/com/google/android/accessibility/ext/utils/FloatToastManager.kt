package com.google.android.accessibility.ext.utils

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.WindowManager.LayoutParams
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.selecttospeak.accessibilityService

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/4/24  14:07
 * Description:This is FloatToastManager
 */
object FloatToastManager {

    private var windowManager: WindowManager? = null
    private var floatLayout: LinearLayout? = null
    private var textView: TextView? = null
    private var imageView: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    /**
     * 显示悬浮窗 Toast
     * 自动初始化悬浮窗
     *
     * @param context Context
     * @param message 提示文本
     * @param duration 显示时长
     * @param packageName 应用包名，可获取应用图标
     */
    fun showFloatToast(
        message: String,
        duration: Long = 2000L,
        packageName: String? = null
    ) {
        val context: Context = accessibilityService ?: appContext

        // 自动初始化
        if (floatLayout == null) {
            if (windowManager == null) {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            // 横向布局，左侧图标，右侧文字
            floatLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(context, 8), dpToPx(context, 8), dpToPx(context, 8), dpToPx(context, 8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0x88000000.toInt())
                    // cornerRadius 先设置0，之后动态更新
                    cornerRadius = 0f
                }
                visibility = View.GONE
            }

            // 延迟设置 cornerRadius 为高度的一半，使左右圆角为椭圆
            floatLayout?.let { layout ->
                layout.post {
                    (layout.background as? GradientDrawable)?.let { bg ->
                        bg.cornerRadius = layout.height / 2f
                    }
                }
            }

            // 设置固定尺寸，防止测量为0
            val size = dpToPx(context, 30)
            imageView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dpToPx(context, 8)
                }
            }
            // 占位图可见测试
            imageView?.setImageDrawable(getPlaceholderDrawable(context))
            textView = TextView(context).apply {
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
            }
            floatLayout?.minimumHeight = size + dpToPx(context, 10)
            floatLayout?.addView(imageView)
            floatLayout?.addView(textView)

            // 确定 Window 类型
            val windowType = when {
                accessibilityService != null -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    else
                        LayoutParams.TYPE_PHONE
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    if (Settings.canDrawOverlays(context))
                        LayoutParams.TYPE_APPLICATION_OVERLAY
                    else {
                        //requestOverlayPermission()
                        return
                    }
                }
                else -> LayoutParams.TYPE_PHONE
            }


            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                LayoutParams.FLAG_NOT_FOCUSABLE
                        or LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = 200

            try {
                windowManager?.addView(floatLayout, params)
            } catch (_: Exception) {
            }
        }

        // 更新内容
        textView?.text = message
        val icon = packageName?.let { getAppIcon(context, it) } ?: getPlaceholderDrawable(context)
        imageView?.setImageDrawable(icon)

        floatLayout?.visibility = View.VISIBLE

        // 隐藏控制
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable { floatLayout?.visibility = View.GONE }
            .also { handler.postDelayed(it, duration) }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    private fun getPlaceholderDrawable(context: Context): Drawable {
        // 透明 drawable 占位
        return GradientDrawable().apply {
            val size = dpToPx(context, 30) // 和 ImageView 一致
            setSize(size, size)
            setColor(0x00000000) // 完全透明
        }
    }
    fun hide() {
        floatLayout?.visibility = View.GONE
        hideRunnable?.let { handler.removeCallbacks(it) }
    }

    /** 获取应用图标 */
    private fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }
}