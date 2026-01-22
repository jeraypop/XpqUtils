package com.google.android.accessibility.ext.window

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import com.google.android.accessibility.ext.window.AssistsWindowManager.pxFromDp

/**
 * SwipeTrajectoryIndicatorManager
 *
 * - 复用窗口实例，避免重复创建销毁
 * - 轨迹颜色为红色系，前端圆点有呼吸动画
 * - 使用：SwipeTrajectoryIndicatorManager.show(context, path, duration = 600L)
 * - 隐藏：SwipeTrajectoryIndicatorManager.hide()
 */
object SwipeTrajectoryIndicatorManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val TAG = "SwipeTrajectoryIndicator"

    private const val DEFAULT_DURATION_MS: Long = 1000L
    private const val DEFAULT_STROKE_DP = 6
    private const val DEFAULT_TRAIL_FRACTION = 0.25f

    // 缓存 WindowManager 实例
    @Volatile
    private var cachedWindowManager: WindowManager? = null

    // 复用的轨迹视图
    @Volatile
    private var cachedTrajectoryView: TrajectoryOverlayView? = null
    private var isWindowAdded = false

    /**
     * 显示轨迹动画（path 为屏幕坐标系，单位 px）
     *
     * @param context 建议传 applicationContext
     * @param path 屏幕坐标系 Path（px）
     * @param duration 动画时长（ms）
     * @param strokeDp 轨迹线宽（dp）
     * @param trailFraction 轨迹尾迹占比（0..1）
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
                    val wm = getWindowManager(context) ?: return@post

                    // 初始化窗口（仅首次）
                    ensureWindow(context, wm)

                    // 更新轨迹参数
                    updateTrajectory(path, duration, strokeDp, trailFraction)

                } catch (t: Throwable) {
                    Log.e(TAG, "show failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post failed: ${t.message}")
        }
    }

    /**
     * 立即隐藏 overlay（若存在）
     */
    fun hide() {
        try {
            mainHandler.post {
                try {
                    val view = cachedTrajectoryView
                    if (view != null) {
                        view.stopAnimation()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "hide internal error: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hide post failed: ${t.message}")
        }
    }

    private fun getWindowManager(context: Context): WindowManager? {
        return cachedWindowManager ?: run {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            cachedWindowManager = wm
            wm
        }
    }

    private fun ensureWindow(context: Context, wm: WindowManager) {
        if (isWindowAdded) return

        // 创建复用的轨迹视图
        if (cachedTrajectoryView == null) {
            cachedTrajectoryView = TrajectoryOverlayView(context)
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
            type = AssistsWindowManager.chooseWindowType()
        }

        wm.addView(cachedTrajectoryView!!, lp)
        isWindowAdded = true
    }

    private fun updateTrajectory(
        path: Path,
        duration: Long,
        strokeDp: Int,
        trailFraction: Float
    ) {
        val view = cachedTrajectoryView ?: return
        val strokePx = pxFromDp(view.context, strokeDp)

        // 更新轨迹参数
        view.setPath(path)
        view.trailFraction = trailFraction.coerceIn(0f, 1f)
        view.strokeWidthPx = strokePx.toFloat()

        // 重新启动动画
        view.startAnimation(duration)
    }


    // =========================
    // 内部 TrajectoryOverlayView（轨迹与圆点绘制 + 圆点呼吸动画）
    // =========================
    private class TrajectoryOverlayView(context: Context) : View(context) {
        // 轨迹画笔（红色半透明，带轻微发光以便锁屏上更明显）
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0x88FF3333.toInt() // 半透明红
        }
        // 圆点画笔（纯红，带光晕）
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF4444.toInt() // 纯红点
        }

        private val fullPath = Path()
        private val drawPath = Path()
        private var pm: PathMeasure? = null
        private var pathLen = 0f

        private var progress = 0f
        private var animator: ValueAnimator? = null

        // 圆点呼吸动画
        private var dotPulsePhase = 0f
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

        var trailFraction = DEFAULT_TRAIL_FRACTION
        var strokeWidthPx = pxFromDp(context, DEFAULT_STROKE_DP).toFloat()
        private var dotRadius = (strokeWidthPx * 1.1f).coerceAtLeast(6f)

        init {
            isClickable = false
            isFocusable = false
            trackPaint.strokeWidth = strokeWidthPx
            // 给轨迹和点增加阴影以增强可见度（在某些锁屏上会被忽略）
            try {
                trackPaint.setShadowLayer(6f, 0f, 0f, 0x55FF3333.toInt())
                dotPaint.setShadowLayer(12f, 0f, 0f, 0x66FF4444.toInt())
                // 需要在 view 层启用软件层以显示 shadow（在某些设备上）
                setLayerType(LAYER_TYPE_SOFTWARE, null)
            } catch (_: Throwable) {
            }
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
            dotPulseAnimator.start()
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
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.GONE  // 动画结束后隐藏视图
                    }
                })
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
            dotPulseAnimator.cancel()
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
                // 圆点呼吸：让半径在 0.85x ~ 1.15x 间波动
                val dynamicRadius = dotRadius * (0.85f + 0.3f * dotPulsePhase)
                canvas.drawCircle(dotX, dotY, dynamicRadius, dotPaint)
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            dotPulseAnimator.cancel()
            animator = null
            super.onDetachedFromWindow()
        }
    }

    /**
     * 清理资源
     * 应在应用退出时调用
     */
    fun cleanup() {
        mainHandler.post {
            try {
                if (isWindowAdded) {
                    cachedWindowManager?.let { wm ->
                        cachedTrajectoryView?.stopAnimation()
                        wm.removeViewImmediate(cachedTrajectoryView)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cleanup failed", t)
            } finally {
                isWindowAdded = false
                cachedWindowManager = null
                cachedTrajectoryView?.stopAnimation()
                cachedTrajectoryView = null
            }
        }
    }
}
