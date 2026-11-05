package com.google.android.accessibility.ext.vdisplay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 虚拟屏画面的悬浮预览窗：任务运行时在主屏右上角显示副屏实时画面。
 *
 * - 按住画面拖动可移动位置；
 * - 拖动右下角缩放图标：往右下整体放大，往左上整体缩小；
 *   只横向或只纵向拖动时，只改变对应方向的尺寸；
 * - 右上角关闭图标随时关闭。
 *
 * 关闭/缩放按钮的图标、尺寸、内边距与库内日志悬浮窗（assists_window_layout_wrapper_xpq）一致。
 * 位置和大小会保存，下次继续使用。窗口可触摸但不会抢焦点。
 */
object XpqVDisplayPreview {
    private const val TAG = "XpqVDisplayPreview"
    private const val PREFS = "xpq_vdisplay_preview"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    private const val DEFAULT_WIDTH = 360
    private const val DEFAULT_HEIGHT = 576
    private const val MIN_WIDTH = 180
    private const val MIN_HEIGHT = 180
    // 与日志悬浮窗（assists_window_layout_wrapper_xpq）一致：40dp 图标按钮 + 图标内边距
    private const val BUTTON_SIZE_DP = 40
    private const val CLOSE_PADDING_DP = 12
    private const val HANDLE_PADDING_DP = 10
    private const val BUTTON_MARGIN_DP = 8

    private var root: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private var job: Job? = null
    private var prefs: SharedPreferences? = null
    private var imageView: ImageView? = null
    private var hintView: android.widget.TextView? = null

    /** 是否正在显示预览。 */
    fun isShown(): Boolean = root != null

    /**
     * 显示预览窗。[session] 为 null 时显示占位画面（设置里打开预览开关时用于调整大小/位置），
     * 之后任务建屏时再用 [attach] 接上实时帧。
     *
     * @return 窗口是否成功加上；没有悬浮窗权限或系统拦截时返回 false，调用方可引导用户授权。
     */
    fun show(context: Context, session: XpqVDisplay.Session?): Boolean {
        if (root != null) {
            attach(session)
            return true
        }
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "no overlay permission")
            return false
        }
        val wm = app.getSystemService(WindowManager::class.java) ?: return false
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val savedWidth = sp.getInt(KEY_WIDTH, DEFAULT_WIDTH).takeIf { it > 0 } ?: DEFAULT_WIDTH
        val savedHeight = sp.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).takeIf { it > 0 } ?: DEFAULT_HEIGHT

        val image = ImageView(app).apply {
            setBackgroundColor(0x99000000.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val hint = android.widget.TextView(app).apply {
            text = "任务运行时这里显示副屏画面\n可拖动画面移动、拖右下角缩放"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dpOf(app, 12), dpOf(app, 12), dpOf(app, 12), dpOf(app, 12))
        }
        val container = FrameLayout(app).apply {
            addView(image, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(hint, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val density = app.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val close = ImageView(app).apply {
            setImageResource(com.android.accessibility.ext.R.drawable.icon_close_xpq)
            // 与日志悬浮窗同一套底色（bg_1_xpq：10dp 圆角 + #80000000），白色图标在浅色画面上也看得清
            setBackgroundResource(com.android.accessibility.ext.R.drawable.bg_1_xpq)
            setPadding(
                dp(CLOSE_PADDING_DP), dp(CLOSE_PADDING_DP),
                dp(CLOSE_PADDING_DP), dp(CLOSE_PADDING_DP),
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "关闭"
            setOnClickListener { hide() }
        }
        container.addView(
            close,
            FrameLayout.LayoutParams(
                dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP), Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = dp(BUTTON_MARGIN_DP)
                rightMargin = dp(BUTTON_MARGIN_DP)
            },
        )

        val lp = WindowManager.LayoutParams(
            savedWidth,
            savedHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = sp.getInt(KEY_X, 16)
            y = sp.getInt(KEY_Y, 220)
        }

        // 右下角缩放把手（图标与日志悬浮窗一致）：拖动改变大小
        val resizeHandle = ImageView(app).apply {
            setImageResource(com.android.accessibility.ext.R.drawable.scale_xpq)
            setBackgroundResource(com.android.accessibility.ext.R.drawable.bg_1_xpq)
            setPadding(
                dp(HANDLE_PADDING_DP), dp(HANDLE_PADDING_DP),
                dp(HANDLE_PADDING_DP), dp(HANDLE_PADDING_DP),
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "缩放"
        }
        container.addView(
            resizeHandle,
            FrameLayout.LayoutParams(
                dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP), Gravity.BOTTOM or Gravity.END,
            ).apply {
                bottomMargin = dp(BUTTON_MARGIN_DP)
                rightMargin = dp(BUTTON_MARGIN_DP)
            },
        )

        image.setOnTouchListener(moveTouchListener(app, container, wm, lp))
        resizeHandle.setOnTouchListener(resizeTouchListener(app, container, wm, lp))

        runCatching { wm.addView(container, lp) }
            .onFailure {
                Log.w(TAG, "add preview window failed", it)
                return false
            }
        root = container
        windowManager = wm
        prefs = sp
        imageView = image
        hintView = hint
        attach(session)
        Log.i(TAG, "preview shown ${lp.width}x${lp.height} x=${lp.x} y=${lp.y} session=${session != null}")
        return true
    }

    /** 接上/断开实时帧；[session] 为 null 时保持占位画面。 */
    fun attach(session: XpqVDisplay.Session?) {
        job?.cancel()
        job = null
        if (session == null) {
            hintView?.visibility = View.VISIBLE
            return
        }
        hintView?.visibility = View.GONE
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            session.frames.collect { bmp ->
                if (bmp != null) imageView?.setImageBitmap(bmp)
            }
        }
    }

    private fun dpOf(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 按住画面拖动：移动悬浮窗。 */
    private fun moveTouchListener(
        context: Context,
        container: FrameLayout,
        wm: WindowManager,
        lp: WindowManager.LayoutParams,
    ) = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = lp.x
                startY = lp.y
                startRawX = event.rawX
                startRawY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                // gravity = TOP|END，x 是从右边量起的距离：手指右移则 x 变小
                lp.x = max(0, startX - (event.rawX - startRawX).toInt())
                lp.y = max(0, startY + (event.rawY - startRawY).toInt())
                runCatching { wm.updateViewLayout(container, lp) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                save(context, lp)
            }
        }
        true
    }

    /** 拖动右下角把手：往右下放大、往左上缩小；横向、纵向各自独立改变尺寸。 */
    private fun resizeTouchListener(
        context: Context,
        container: FrameLayout,
        wm: WindowManager,
        lp: WindowManager.LayoutParams,
    ) = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = lp.x
                startWidth = lp.width
                startHeight = lp.height
                startRawX = event.rawX
                startRawY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val metrics = context.resources.displayMetrics
                val maxWidth = metrics.widthPixels - 20
                val maxHeight = metrics.heightPixels - 20
                val newWidth = min(max(startWidth + (event.rawX - startRawX).toInt(), MIN_WIDTH), maxWidth)
                val newHeight = min(max(startHeight + (event.rawY - startRawY).toInt(), MIN_HEIGHT), maxHeight)
                lp.width = newWidth
                lp.height = newHeight
                // gravity = TOP|END，左上角保持不动：宽度增加多少，x 就减少多少
                lp.x = max(0, startX + startWidth - newWidth)
                runCatching { wm.updateViewLayout(container, lp) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                save(context, lp)
            }
        }
        true
    }

    private var startX = 0
    private var startY = 0
    private var startRawX = 0f
    private var startRawY = 0f
    private var startWidth = 0
    private var startHeight = 0

    private fun save(context: Context, lp: WindowManager.LayoutParams) {
        prefs?.edit()
            ?.putInt(KEY_WIDTH, lp.width)
            ?.putInt(KEY_HEIGHT, lp.height)
            ?.putInt(KEY_X, lp.x)
            ?.putInt(KEY_Y, lp.y)
            ?.apply()
    }

    fun hide() {
        job?.cancel()
        job = null
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        windowManager = null
        prefs = null
        imageView = null
        hintView = null
    }
}
