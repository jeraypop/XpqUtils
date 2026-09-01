package com.google.android.accessibility.ext.utils.gestureUtils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.acc.XpqAcc
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 统一拟人化手势与触控引擎 (支持点击、长按、滑动与拖拽)
 */
object HumanTouchEngine {

    // =========================================================================
    // 配置定义
    // =========================================================================

    /** 点击配置 */
    data class ClickConfig(
        val posStdPx: Double = 3.0,          // 落点高斯抖动标准差 (2~4px 最佳)
        val pressMeanMs: Double = 95.0,      // 按压时长期望值 (ms)
        val pressStdMs: Double = 15.0,       // 按压时长标准差 (ms)
        val microMoves: IntRange = 2..4,     // 按压停留期间的微观微移次数
    )

    /** 滑动/拖拽配置 */
    data class SwipeConfig(
        val durationMeanMs: Double = 450.0,  // 滑动时长期望值 (ms)
        val durationStdMs: Double = 50.0,    // 滑动时长标准差 (ms)
        val controlOffsetPx: Double = 60.0,  // 贝塞尔控制点弯曲幅度 (px)
        val jitterStdPx: Double = 1.2,       // 轨迹沿途微观噪点标准差 (px)
        val steps: Int = 35                  // 采样步数 (决定轨迹平滑度)
    )

    // =========================================================================
    // 生命与作用域管理
    // =========================================================================

    private var serviceRef: WeakReference<AccessibilityService>? = null
    private var scope: CoroutineScope? = null

    /**
     * UiAutomation 模式下 attach() 不会被调用（无真实无障碍服务），
     * 而手势派发已走 [XpqAcc] 门面（不依赖具体 service 实例），
     * 故用此兜底 scope 跑协程，保证两条通道都能注入手势。
     */
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun activeScope(): CoroutineScope = scope ?: fallbackScope

    /** 绑定无障碍服务生命周期 */
    fun attach(service: AccessibilityService) {
        serviceRef = WeakReference(service)
        if (scope == null || !scope!!.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
    }

    /** 解绑服务并释放协程与内存资源 */
    fun detach() {
        serviceRef?.clear()
        serviceRef = null
        scope?.cancel()
        scope = null
    }

    fun isAttached(): Boolean = serviceRef?.get() != null

    // =========================================================================
    // 外部调用入口：点击 (Click)
    // =========================================================================

    /**
     * 单次点击（带回调）
     * @return true 表示成功提交手势任务；false 表示服务未绑定或构建失败
     */
    @RequiresApi(Build.VERSION_CODES.N)
    @JvmStatic
    @JvmOverloads
    fun click(
        cx: Float,
        cy: Float,
        config: ClickConfig = ClickConfig(),
        onDone: ((Boolean) -> Unit)? = null
    ): Boolean {
        return dispatchAsync({ buildClickGesture(PointF(cx, cy), config) }, onDone)
    }

    /**
     * 单次点击（协程挂起版）
     * @return true 表示手势派发成功且已完成（onCompleted）；false 表示被取消或派发失败
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun clickAsync(cx: Float, cy: Float, config: ClickConfig = ClickConfig()): Boolean {
        val gesture = buildClickGesture(PointF(cx, cy), config)
        return performGesture(gesture)
    }

    // =========================================================================
    // 外部调用入口：滑动/拖拽 (Swipe)
    // =========================================================================

    /**
     * 滑动手势（带回调）
     * @return true 表示成功提交手势任务；false 表示服务未绑定或构建失败
     */
    @RequiresApi(Build.VERSION_CODES.N)
    @JvmStatic
    @JvmOverloads
    fun swipe(
        start: PointF,
        end: PointF,
        config: SwipeConfig = SwipeConfig(),
        onDone: ((Boolean) -> Unit)? = null
    ): Boolean {
        return dispatchAsync({ buildSwipeGesture(start, end, config) }, onDone)
    }

    /**
     * 滑动手势（协程挂起版）
     * @return true 表示手势派发成功且已完成（onCompleted）；false 表示被取消或派发失败
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun swipeAsync(
        start: PointF,
        end: PointF,
        config: SwipeConfig = SwipeConfig()
    ): Boolean {
        val gesture = buildSwipeGesture(start, end, config)
        return performGesture(gesture)
    }

    // =========================================================================
    // 手势构建算法逻辑
    // =========================================================================

    /** 构建点击 Path 与 GestureDescription */
    @RequiresApi(Build.VERSION_CODES.N)
    fun buildClickGesture(target: PointF, config: ClickConfig = ClickConfig()): GestureDescription {
        var currX = (target.x + gaussian(0.0, config.posStdPx)).toFloat().coerceAtLeast(1f)
        var currY = (target.y + gaussian(0.0, config.posStdPx)).toFloat().coerceAtLeast(1f)

        val path = Path().apply {
            moveTo(currX, currY)
            repeat(config.microMoves.random()) {
                currX = (currX + gaussian(0.0, 0.5)).toFloat().coerceAtLeast(0f)
                currY = (currY + gaussian(0.0, 0.5)).toFloat().coerceAtLeast(0f)
                lineTo(currX, currY)
            }
        }

        val duration = gaussian(config.pressMeanMs, config.pressStdMs).toLong().coerceIn(50L, 200L)
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build()
    }

    /** 构建三阶贝塞尔 + Ease-InOut 变速滑动 Path */
    @RequiresApi(Build.VERSION_CODES.N)
    fun buildSwipeGesture(start: PointF, end: PointF, config: SwipeConfig = SwipeConfig()): GestureDescription {
        val realStart = PointF(
            (start.x + gaussian(0.0, 3.0)).toFloat().coerceAtLeast(1f),
            (start.y + gaussian(0.0, 3.0)).toFloat().coerceAtLeast(1f)
        )
        val realEnd = PointF(
            (end.x + gaussian(0.0, 4.0)).toFloat().coerceAtLeast(1f),
            (end.y + gaussian(0.0, 4.0)).toFloat().coerceAtLeast(1f)
        )

        val dx = realEnd.x - realStart.x
        val dy = realEnd.y - realStart.y

        val p1 = PointF(
            (realStart.x + dx * 0.25f + gaussian(0.0, config.controlOffsetPx)).toFloat(),
            (realStart.y + dy * 0.25f + gaussian(0.0, config.controlOffsetPx)).toFloat()
        )
        val p2 = PointF(
            (realStart.x + dx * 0.75f + gaussian(0.0, config.controlOffsetPx)).toFloat(),
            (realStart.y + dy * 0.75f + gaussian(0.0, config.controlOffsetPx)).toFloat()
        )

        val path = Path().apply {
            moveTo(realStart.x, realStart.y)
            for (i in 1..config.steps) {
                val linearProgress = i.toFloat() / config.steps
                val easedProgress = easeInOutCubic(linearProgress)
                val bezierPt = calculateCubicBezierPoint(easedProgress, realStart, p1, p2, realEnd)

                val jitterX = if (i == config.steps) 0.0 else gaussian(0.0, config.jitterStdPx)
                val jitterY = if (i == config.steps) 0.0 else gaussian(0.0, config.jitterStdPx)

                lineTo((bezierPt.x + jitterX).toFloat().coerceAtLeast(0f), (bezierPt.y + jitterY).toFloat().coerceAtLeast(0f)
                )
            }
        }

        val duration = gaussian(config.durationMeanMs, config.durationStdMs).toLong().coerceIn(200L, 1200L)
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build()
    }

    // =========================================================================
    // 内部执行与通用数学工具
    // =========================================================================

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchAsync(
        gestureBlock: () -> GestureDescription,
        onDone: ((Boolean) -> Unit)?
    ): Boolean {
        // 无障碍模式用 attach 的 scope；UiAutomation 模式用 fallbackScope。
        // 派发本身已走 XpqAcc 门面，不再依赖 service 实例，故不因 serviceRef 为空而失败。
        val currentScope = activeScope()

        currentScope.launch {
            val success = performGesture(gestureBlock())
            onDone?.let {
                withContext(Dispatchers.Main) { it(success) }
            }
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun performGesture(gesture: GestureDescription): Boolean {
        return performGestureInternal(gesture)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun performGestureInternal(
        gesture: GestureDescription
    ): Boolean {
        return suspendCancellableCoroutine { cont ->
            try {
                val dispatched = XpqAcc.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(g: GestureDescription) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /** 贝塞尔曲线计算 */
    private fun calculateCubicBezierPoint(t: Float, p0: PointF, p1: PointF, p2: PointF, p3: PointF): PointF {
        val u = 1 - t
        val tt = t * t
        val uu = u * u
        val uuu = uu * u
        val ttt = tt * t
        val x = uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x
        val y = uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y
        return PointF(x, y)
    }

    /** 缓动函数 (Ease-InOut Cubic) */
    private fun easeInOutCubic(x: Float): Float =
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

    /** 高斯分布（Box-Muller 变换） */
    private fun gaussian(mean: Double, std: Double): Double {
        val u1 = Random.nextDouble().coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        return mean + std * sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** 拟人高斯随机延迟 (ms) */
    @JvmStatic
    @JvmOverloads
    fun randomDelayMs(base: Long = 1000, jitter: Long = 800): Long {
        val mean = base + jitter / 2.0
        val std = jitter / 4.0
        return gaussian(mean, std).toLong().coerceAtLeast(base)
    }
}