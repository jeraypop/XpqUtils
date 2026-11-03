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
import android.widget.TextView
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
 * - 拖动右下角「⤡」：往右下整体放大，往左上整体缩小；
 *   只横向或只纵向拖动时，只改变对应方向的尺寸；
 * - 右上角「×」随时关闭。
 *
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
    private const val CLOSE_SIZE = 84
    private const val HANDLE_SIZE = 96

    private var root: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private var job: Job? = null
    private var prefs: SharedPreferences? = null

    /** 是否正在显示预览。 */
    fun isShown(): Boolean = root != null

    fun show(context: Context, session: XpqVDisplay.Session) {
        if (root != null) return
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "no overlay permission")
            return
        }
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val savedWidth = sp.getInt(KEY_WIDTH, DEFAULT_WIDTH).takeIf { it > 0 } ?: DEFAULT_WIDTH
        val savedHeight = sp.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).takeIf { it > 0 } ?: DEFAULT_HEIGHT

        val image = ImageView(app).apply {
            setBackgroundColor(0x99000000.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val container = FrameLayout(app).apply {
            addView(image, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val close = TextView(app).apply {
            text = "×"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            setOnClickListener { hide() }
        }
        container.addView(
            close,
            FrameLayout.LayoutParams(CLOSE_SIZE, CLOSE_SIZE, Gravity.TOP or Gravity.END).apply {
                topMargin = 8
                rightMargin = 8
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

        // 右下角缩放把手：拖动改变大小
        val resizeHandle = TextView(app).apply {
            text = "⤡"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
        }
        container.addView(
            resizeHandle,
            FrameLayout.LayoutParams(HANDLE_SIZE, HANDLE_SIZE, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = 8
                rightMargin = 8
            },
        )

        image.setOnTouchListener(moveTouchListener(app, container, wm, lp))
        resizeHandle.setOnTouchListener(resizeTouchListener(app, container, wm, lp))

        runCatching { wm.addView(container, lp) }
            .onFailure {
                Log.w(TAG, "add preview window failed", it)
                return
            }
        root = container
        windowManager = wm
        prefs = sp
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            session.frames.collect { bmp ->
                if (bmp != null) image.setImageBitmap(bmp)
            }
        }
        Log.i(TAG, "preview shown ${lp.width}x${lp.height} x=${lp.x} y=${lp.y}")
    }

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
    }
}
