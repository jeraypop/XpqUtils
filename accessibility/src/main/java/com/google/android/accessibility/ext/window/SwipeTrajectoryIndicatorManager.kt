package com.google.android.accessibility.ext.window

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import java.util.WeakHashMap

/**
 * SwipeTrajectoryIndicatorManager（稳定版）
 *
 * 稳定性增强点：
 * 1. show 限流，防止高频 add/remove Window
 * 2. Animator 生命周期强制收口
 * 3. removeRunnableMap 使用 WeakHashMap，防止 View 泄漏
 * 4. 统一 safeRemove，保证所有路径都能正确释放
 */
object SwipeTrajectoryIndicatorManager {

    private const val TAG = "SwipeTrajectoryIndicator"

    private const val DEFAULT_DURATION_MS = 1000L
    private const val DEFAULT_STROKE_DP = 6
    private const val DEFAULT_TRAIL_FRACTION = 0.25f

    // ★稳定性修改：show 限流
    private const val MIN_SHOW_INTERVAL_MS = 80L
    @Volatile private var lastShowTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var wm: WindowManager? = null
    @Volatile private var overlayView: TrajectoryOverlayView? = null

    // ★稳定性修改：WeakHashMap 防止 View 被强引用
    private val removeRunnableMap =
        WeakHashMap<TrajectoryOverlayView, Runnable>()

    /**
     * 显示滑动轨迹
     */
    fun show(
        context: Context,
        path: Path,
        duration: Long = DEFAULT_DURATION_MS,
        strokeDp: Int = DEFAULT_STROKE_DP,
        trailFraction: Float = DEFAULT_TRAIL_FRACTION
    ) {
        val now = System.currentTimeMillis()
        if (now - lastShowTime < MIN_SHOW_INTERVAL_MS) return
        lastShowTime = now

        mainHandler.post {
            try {
                if (wm == null) {
                    wm = context.applicationContext
                        .getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                }
                val windowManager = wm ?: return@post

                // ★稳定性修改：清理旧 overlay
                overlayView?.let { old ->
                    removeRunnableMap.remove(old)?.let {
                        mainHandler.removeCallbacks(it)
                    }
                    safeRemove(windowManager, old)
                    overlayView = null
                }

                val strokePx = pxFromDp(context, strokeDp)

                val view = TrajectoryOverlayView(context).apply {
                    setPath(path)
                    this.trailFraction = trailFraction.coerceIn(0f, 1f)
                    this.strokeWidthPx = strokePx.toFloat()
                }

                val lp = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    format = PixelFormat.TRANSLUCENT
                    flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    gravity = Gravity.TOP or Gravity.START
                    type = chooseWindowType()
                }

                windowManager.addView(view, lp)
                overlayView = view

                view.startAnimation(duration)

                // ★稳定性修改：统一移除 Runnable
                val removeRunnable = Runnable {
                    safeRemove(windowManager, view)
                    removeRunnableMap.remove(view)
                    if (overlayView === view) overlayView = null
                }

                removeRunnableMap[view] = removeRunnable
                mainHandler.postDelayed(removeRunnable, duration + 300L)

            } catch (t: Throwable) {
                Log.w(TAG, "show error: ${t.message}")
            }
        }
    }

    /**
     * 立即隐藏
     */
    fun hide() {
        mainHandler.post {
            try {
                val windowManager = wm ?: return@post
                val view = overlayView ?: return@post

                removeRunnableMap.remove(view)?.let {
                    mainHandler.removeCallbacks(it)
                }
                safeRemove(windowManager, view)
                overlayView = null

            } catch (t: Throwable) {
                Log.w(TAG, "hide error: ${t.message}")
            }
        }
    }

    // ★稳定性修改：统一安全移除
    private fun safeRemove(
        windowManager: WindowManager,
        view: TrajectoryOverlayView
    ) {
        try {
            view.stopAnimation()
        } catch (_: Throwable) {}

        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Throwable) {
            try { windowManager.removeView(view) } catch (_: Throwable) {}
        }
    }

    private fun chooseWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= 28)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun pxFromDp(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    // =========================
    // TrajectoryOverlayView
    // =========================
    private class TrajectoryOverlayView(context: Context) : View(context) {

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0x88FF3333.toInt()
        }

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF4444.toInt()
        }

        private val fullPath = Path()
        private val drawPath = Path()
        private var pathMeasure: PathMeasure? = null
        private var pathLength = 0f

        private var progress = 0f
        private var animator: ValueAnimator? = null

        // ★稳定性修改：呼吸动画受控启动/停止
        private val dotPulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                dotPulsePhase = it.animatedValue as Float
                invalidate()
            }
        }

        private var dotPulsePhase = 0f

        var trailFraction = DEFAULT_TRAIL_FRACTION
        var strokeWidthPx = 6f
        private var dotRadius = 8f

        private var dotX = 0f
        private var dotY = 0f

        init {
            isClickable = false
            isFocusable = false
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            trackPaint.setShadowLayer(6f, 0f, 0f, 0x55FF3333.toInt())
            dotPaint.setShadowLayer(12f, 0f, 0f, 0x66FF4444.toInt())
        }

        fun setPath(path: Path) {
            animator?.cancel()
            fullPath.set(path)
            pathMeasure = PathMeasure(fullPath, false)
            pathLength = pathMeasure?.length ?: 0f
            drawPath.reset()
            progress = 0f
        }

        fun startAnimation(durationMs: Long) {
            stopAnimation()

            if (pathLength <= 0f) return

            trackPaint.strokeWidth = strokeWidthPx
            dotRadius = (strokeWidthPx * 1.1f).coerceAtLeast(6f)

            dotPulseAnimator.start()

            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs.coerceAtLeast(50L)
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    buildDrawPath()
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
            if (dotPulseAnimator.isRunning) {
                dotPulseAnimator.cancel()
            }
            drawPath.reset()
            invalidate()
        }

        private fun buildDrawPath() {
            drawPath.reset()
            val pm = pathMeasure ?: return
            val curLen = pathLength * progress
            val trailLen = pathLength * trailFraction
            val start = (curLen - trailLen).coerceAtLeast(0f)
            pm.getSegment(start, curLen, drawPath, true)

            val pos = FloatArray(2)
            pm.getPosTan(curLen.coerceAtMost(pathLength), pos, null)
            dotX = pos[0]
            dotY = pos[1]
        }

        override fun onDraw(canvas: Canvas) {
            if (!drawPath.isEmpty) {
                canvas.drawPath(drawPath, trackPaint)
                val r = dotRadius * (0.85f + 0.3f * dotPulsePhase)
                canvas.drawCircle(dotX, dotY, r, dotPaint)
            }
        }

        override fun onDetachedFromWindow() {
            stopAnimation()
            super.onDetachedFromWindow()
        }
    }
}
