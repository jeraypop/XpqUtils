package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.PathMeasure
import android.graphics.Point
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.ext.utils.LibCtxProvider
import com.google.android.accessibility.uiautomation.engine.InvisibleAutomation

/**
 * UiAutomation 通道：包装移植自 accessibilityLibs 的 [InvisibleAutomation]。
 * 免开无障碍，用 Shizuku shell 身份注册 registerUiTestAutomationService。
 */
object UiAutomationDriver : AccDriver {

    override val mode get() = EngineMode.UIAUTOMATION
    override val isConnected get() = InvisibleAutomation.isConnected

    /** 最近一次连接的失败原因（简短），连接成功时为空。 */
    val lastError: String? get() = InvisibleAutomation.lastError

    override fun connect(onLog: (String) -> Unit): Boolean {
        val ctx = runCatching { LibCtxProvider.Companion.appContext }.getOrNull()
        if (ctx == null) {
            onLog("✗ UiAutomation 连接失败：appContext 未初始化")
            return false
        }
        return InvisibleAutomation.connect(ctx, 15000L, onLog)
    }

    override fun disconnect() = InvisibleAutomation.disconnect()

    /** 探测 system_server 是否已有 UiAutomation 注册（被其它 App/进程占用）。 */
    fun isUiAutomationOccupied(): Boolean = InvisibleAutomation.isUiAutomationOccupied()

    override fun rootInActiveWindow(): AccessibilityNodeInfo? = InvisibleAutomation.getRoot()
    override fun windows(): List<AccessibilityWindowInfo>? = InvisibleAutomation.getWindows()
    override fun findFocus(focusType: Int): AccessibilityNodeInfo? = InvisibleAutomation.findFocus(focusType)

    override fun performGlobalAction(action: Int): Boolean = InvisibleAutomation.performGlobalAction(action)

    override fun dispatchGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?
    ): Boolean {
        val (points, duration) = gestureToPoints(gesture)
        if (points.isEmpty()) {
            callback?.onCancelled(gesture)
            return false
        }
        val ok = InvisibleAutomation.dispatchGesture(points, maxOf(1L, duration))
        if (ok) callback?.onCompleted(gesture) else callback?.onCancelled(gesture)
        return ok
    }

    override fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?) {
        InvisibleAutomation.setOnAccessibilityEventListener(listener)
    }

    /** GestureDescription → 轨迹点列表（PathMeasure 采样 path），返回 (轨迹点, 时长ms)。 */
    private fun gestureToPoints(g: GestureDescription): Pair<List<Point>, Long> {
        val points = mutableListOf<Point>()
        var duration = 0L
        for (i in 0 until g.strokeCount) {
            val stroke = g.getStroke(i)
            // 滑动时长只取 stroke.duration（startTime 是延迟启动时间，input swipe 无延迟概念，
            // 若把 startTime 也累加会导致滑动时长翻倍、速度减半，上划解锁等快速 fling 场景失败）。
            duration = maxOf(duration, stroke.duration)
            val pm = PathMeasure(stroke.path, false)
            val len = pm.length
            if (len <= 0f) continue
            val steps = (stroke.duration / 10L).toInt().coerceIn(2, 40)
            for (j in 0..steps) {
                val pos = FloatArray(2)
                pm.getPosTan(len * j / steps, pos, null)
                points.add(Point(pos[0].toInt(), pos[1].toInt()))
            }
        }
        return points to duration
    }
}
