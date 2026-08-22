package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.*
import kotlin.random.Random
import java.lang.ref.WeakReference

object MyTouchGenerator {

    data class Config(
        val posStdPx: Double = 12.0,          // 落点坐标抖动半径
        val pressMs: LongRange = 60L..120L,   // 按压时长
        val microMoves: IntRange = 2..4,      // 按压中的微移点数
    )

    private var serviceRef: WeakReference<AccessibilityService>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun attach(service: AccessibilityService) {
        serviceRef = WeakReference(service)
    }

    fun detach() {
        serviceRef = null
    }

    fun isAttached(): Boolean = serviceRef?.get() != null

    /** 单次拟人点击，任何地方都能调 */
    @RequiresApi(Build.VERSION_CODES.N)
    @JvmStatic
    @JvmOverloads
    fun click(cx: Float, cy: Float, config: Config = Config(), onDone: Runnable? = null) {
        scope.launch {
            val service = serviceRef?.get() ?: return@launch
            tap(service, cx, cy, config)
            onDone?.run()
        }
    }

    // ---- 内部 ----

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun tap(
        service: AccessibilityService,
        cx: Float, cy: Float, cfg: Config
    ) {
        val x = (cx + gaussian(0.0, cfg.posStdPx)).toFloat().coerceAtLeast(1f)
        val y = (cy + gaussian(0.0, cfg.posStdPx)).toFloat().coerceAtLeast(1f)

        val path = Path().apply {
            moveTo(x, y)
            repeat(cfg.microMoves.random()) {
                lineTo(x + gaussian(0.0, 1.5).toFloat(), y + gaussian(0.0, 1.5).toFloat())
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, cfg.pressMs.random())
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        suspendCancellableCoroutine { cont ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { cont.resume(Unit) }
                override fun onCancelled(g: GestureDescription) { cont.resume(Unit) }
            }, null)
        }
    }

    private fun gaussian(mean: Double, std: Double): Double {
        val u1 = Random.nextDouble().coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        return mean + std * sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }

    /**
     * 生成拟人随机间隔，范围 [base, base + jitter]
     * 默认 250 + [0,200]，即 250~450ms
     */
    @JvmStatic
    @JvmOverloads
    fun randomDelayMs(base: Long = 550, jitter: Long = 650): Long =
        base + Random.nextLong(jitter + 1)
}