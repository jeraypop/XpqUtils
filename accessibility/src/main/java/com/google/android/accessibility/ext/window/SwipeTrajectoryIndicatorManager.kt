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
import androidx.annotation.MainThread

object SwipeTrajectoryIndicatorManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val TAG = "SwipeTrajectoryIndicator"

    private const val DEFAULT_DURATION_MS: Long = 600L
    private const val DEFAULT_STROKE_DP = 6
    private const val DEFAULT_TRAIL_FRACTION = 0.25f

    @Volatile
    private var wm: WindowManager? = null

    @Volatile
    private var overlayView: TrajectoryOverlayView? = null

    private val removeRunnableMap = mutableMapOf<TrajectoryOverlayView, Runnable>()

    /**
     * 显示轨迹动画（path 为屏幕坐标系，单位 px）
     */
    fun show(
        context: Context,
        path: Path,
        duration: Long = DEFAULT_DURATION_MS,
        strokeDp: Int = DEFAULT_STROKE_DP,
        trailFraction: Float = DEFAULT_TRAIL_FRACTION
    ) {
        try {
            mainHandler.post {
                try {
                    if (wm == null) wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    val windowManager = wm
                    if (windowManager == null) {
                        Log.w(TAG, "WindowManager null, cannot show trajectory")
                        return@post
                    }

                    // 清理已有 overlay（避免重叠）
                    overlayView?.let { old ->
                        try {
                            removeRunnableMap.remove(old)?.let { mainHandler.removeCallbacks(it) }
                            windowManager.removeViewImmediate(old)
                        } catch (t: Throwable) {
                            try { windowManager.removeView(old) } catch (_: Throwable) {}
                        }
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
                        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        gravity = Gravity.TOP or Gravity.START
                        type = chooseWindowType()
                    }

                    try {
                        windowManager.addView(view, lp)
                        overlayView = view
                        Log.d(TAG, "added trajectory overlay, duration=$duration, stroke=${strokePx}px")
                    } catch (addEx: Throwable) {
                        Log.w(TAG, "addView failed: ${addEx.message}")
                        try {
                            windowManager.addView(view, lp)
                            overlayView = view
                        } catch (t: Throwable) {
                            Log.w(TAG, "second addView also failed: ${t.message}")
                            return@post
                        }
                    }

                    // 启动动画并计划移除
                    view.startAnimation(duration)

                    val removeRunnable = Runnable {
                        try { view.stopAnimation() } catch (_: Throwable) {}
                        try { windowManager.removeViewImmediate(view) } catch (t: Throwable) {
                            try { windowManager.removeView(view) } catch (_: Throwable) {}
                        } finally {
                            removeRunnableMap.remove(view)
                            if (overlayView === view) overlayView = null
                        }
                    }
                    removeRunnableMap[view] = removeRunnable
                    mainHandler.postDelayed(removeRunnable, duration + 300L)

                } catch (t: Throwable) {
                    Log.w(TAG, "show internal error: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post failed: ${t.message}")
        }
    }

    /**
     * 立即隐藏
     */
    fun hide() {
        try {
            mainHandler.post {
                try {
                    val windowManager = wm
                    val v = overlayView
                    if (windowManager != null && v != null) {
                        removeRunnableMap.remove(v)?.let { mainHandler.removeCallbacks(it) }
                        try { v.stopAnimation() } catch (_: Throwable) {}
                        try { windowManager.removeViewImmediate(v) } catch (t: Throwable) {
                            try { windowManager.removeView(v) } catch (_: Throwable) {}
                        } finally {
                            if (overlayView === v) overlayView = null
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "hide internal error: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hide post failed: ${t.message}")
        }
    }

    private fun chooseWindowType(): Int {
        return try {
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
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun pxFromDp(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    // =========================
    // 内部 TrajectoryOverlayView
    // =========================
    private class TrajectoryOverlayView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xAA33B5E5.toInt()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF33B5E5.toInt()
        }

        private val fullPath = Path()
        private val drawPath = Path()
        private var pm: PathMeasure? = null
        private var pathLen = 0f

        private var progress = 0f
        private var animator: ValueAnimator? = null
        var trailFraction = DEFAULT_TRAIL_FRACTION
        var strokeWidthPx = pxFromDp(context, DEFAULT_STROKE_DP).toFloat()
        private var dotRadius = (strokeWidthPx * 1.1f).coerceAtLeast(6f)

        init {
            isClickable = false
            isFocusable = false
            trackPaint.strokeWidth = strokeWidthPx
        }

        fun setPath(path: Path) {
            animator?.cancel()
            fullPath.reset()
            fullPath.set(path)
            pm = PathMeasure(fullPath, false)
            pathLen = pm?.length ?: 0f
            progress = 0f
            drawPath.reset()
            invalidate()
        }

        fun startAnimation(durationMs: Long = DEFAULT_DURATION_MS) {
            animator?.cancel()
            if (pathLen <= 0f) {
                visibility = GONE
                return
            }
            visibility = VISIBLE
            trackPaint.strokeWidth = strokeWidthPx
            dotRadius = (strokeWidthPx * 1.1f).coerceAtLeast(6f)

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
            progress = 0f
            drawPath.reset()
            visibility = GONE
            invalidate()
        }

        private var dotX = 0f
        private var dotY = 0f

        private fun buildDrawPath() {
            drawPath.reset()
            val m = pm ?: return
            val curPos = FloatArray(2)
            val curLen = (pathLen * progress).coerceAtMost(pathLen)
            val trailLen = (pathLen * trailFraction).coerceAtMost(pathLen)
            val start = (curLen - trailLen).coerceAtLeast(0f)
            val segLen = (curLen - start).coerceAtLeast(0f)

            m.getSegment(start, start + segLen, drawPath, true)
            m.getPosTan(curLen.coerceAtMost(pathLen), curPos, null)
            dotX = curPos[0]
            dotY = curPos[1]
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!drawPath.isEmpty) {
                canvas.drawPath(drawPath, trackPaint)
                canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }
    }
}
