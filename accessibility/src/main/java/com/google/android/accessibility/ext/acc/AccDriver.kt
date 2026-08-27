package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 无障碍能力原子抽象。
 *
 * 只有「取根/窗口/焦点、全局动作、手势注入」这三个通道相关能力需要抽象；
 * 节点查询/点击（AccessibilityNodeInfo 本身的 findNodesById / performAction）跨通道通用，不在此列。
 *
 * 切换见 [XpqAcc]。宿主业务代码零改动，只通过 [XpqAcc] 一次性配置模式。
 */
interface AccDriver {
    val mode: EngineMode
    val isConnected: Boolean

    fun connect(onLog: (String) -> Unit = {}): Boolean
    fun disconnect()

    fun rootInActiveWindow(): AccessibilityNodeInfo?
    fun windows(): List<AccessibilityWindowInfo>?
    fun findFocus(focusType: Int): AccessibilityNodeInfo?

    fun performGlobalAction(action: Int): Boolean
    fun dispatchGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?
    ): Boolean

    /**
     * 事件桥接：UiAutomation 通道把无障碍事件转发到宿主回调；
     * 无障碍通道由系统直接回调 SelectToSpeakServiceAbstract，此方法留空。
     */
    fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?)
}

enum class EngineMode { ACCESSIBILITY_SERVICE, UIAUTOMATION }
