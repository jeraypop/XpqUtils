package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.selecttospeak.accessibilityService

/**
 * 传统无障碍服务通道：直接包装全局 [accessibilityService] 实例（现有行为，零改动）。
 */
object A11yDriver : AccDriver {

    override val mode get() = EngineMode.ACCESSIBILITY_SERVICE
    override val isConnected get() = accessibilityService != null

    private fun svc(): AccessibilityService? = accessibilityService

    override fun connect(onLog: (String) -> Unit): Boolean {
        val ok = isConnected
        onLog(if (ok) "无障碍通道已就绪（服务已开启）" else "无障碍通道未就绪（请在系统设置开启无障碍服务）")
        return ok
    }

    override fun disconnect() {
        // 无障碍服务由系统常驻，不主动断开
    }

    override fun rootInActiveWindow(): AccessibilityNodeInfo? = svc()?.rootInActiveWindow
    override fun windows(): List<AccessibilityWindowInfo>? = svc()?.windows
    override fun findFocus(focusType: Int): AccessibilityNodeInfo? = svc()?.findFocus(focusType)

    override fun performGlobalAction(action: Int): Boolean =
        svc()?.performGlobalAction(action) ?: false

    override fun dispatchGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?
    ): Boolean = svc()?.dispatchGesture(gesture, callback, handler) ?: false

    override fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?) {
        // 无障碍通道由系统直接回调 SelectToSpeakServiceAbstract.onAccessibilityEvent，无需桥接
    }
}
