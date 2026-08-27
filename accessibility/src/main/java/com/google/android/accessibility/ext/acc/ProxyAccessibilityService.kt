package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 代理无障碍服务：UiAutomation 模式下，让全局 accessibilityService 指向本实例，
 * 覆盖非 final 的取根/窗口/焦点方法，委托给 [XpqAcc]（实际路由到 UiAutomation）。
 *
 * 关键：performGlobalAction / dispatchGesture 是 AccessibilityService 的 final 方法，
 * 无法覆盖，由库内 3 处封装点（pressBackButton / gestureScroll / HumanTouchEngine）改走 [XpqAcc]。
 * 本实例不会被系统绑定，仅作为「能力代理」被既有扩展函数当作 AccessibilityService 使用，
 * 因此宿主所有 `accessibilityService?.xxx()` 调用零改动即可在 UiAutomation 模式下工作。
 */
class ProxyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun getRootInActiveWindow(): AccessibilityNodeInfo? = XpqAcc.rootInActiveWindow()

    override fun getWindows(): List<AccessibilityWindowInfo> = XpqAcc.windows() ?: emptyList()

    override fun findFocus(focusType: Int): AccessibilityNodeInfo? = XpqAcc.findFocus(focusType)
}
