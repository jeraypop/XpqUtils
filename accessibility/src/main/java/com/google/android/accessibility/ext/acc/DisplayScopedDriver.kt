package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.ext.vdisplay.XpqVDisplay

/**
 * 把 Xpq 的「根节点来源」切到虚拟屏。
 *
 * Xpq 的所有页面操作（findById / click / inputText …）最终都走 [XpqAcc.rootInActiveWindow]，
 * 也就是 [XpqAcc.driver] 的 rootInActiveWindow()。这里包一层 driver：
 * 根节点、窗口列表、焦点、返回键都换成虚拟屏那一份，其余操作委托给原 driver。
 *
 * 窗口来源由 [windowsProvider] 决定，两种通道都支持：
 *  - 无障碍模式：真实 AccessibilityService 的 `windowsOnAllDisplays`（公开 API）
 *  - UiAutomation 模式：`UiAutomation.getWindowsOnAllDisplays`（@hide）
 */
class DisplayScopedDriver(
    private val base: AccDriver,
    @Volatile var displayId: Int,
    /** 取指定显示上的窗口；由 [XpqAcc.useDisplay] 按当前通道提供。 */
    private val windowsProvider: (Int) -> List<AccessibilityWindowInfo>,
) : AccDriver {

    override val mode: EngineMode get() = base.mode
    override val isConnected: Boolean get() = base.isConnected

    override fun connect(onLog: (String) -> Unit): Boolean = base.connect(onLog)
    override fun disconnect() = base.disconnect()

    override fun rootInActiveWindow(): AccessibilityNodeInfo? {
        val windows = windowsProvider(displayId)
        if (windows.isEmpty()) return null
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
        // 虚拟屏上的活动窗口在无障碍里可能只标 focused=true、active=false（OWN_FOCUS 屏），
        // 所以不能只看 isActive；再按焦点、面积（全屏窗口）兜底，避免选到小浮层。
        val target = apps.firstOrNull { it.isActive }
            ?: apps.firstOrNull { it.isFocused }
            ?: apps.maxByOrNull {
                val rect = android.graphics.Rect()
                it.getBoundsInScreen(rect)
                rect.width() * rect.height()
            }
            ?: windows.lastOrNull()
        return target?.root
    }

    override fun windows(): List<AccessibilityWindowInfo> = windowsProvider(displayId)

    override fun findFocus(focusType: Int): AccessibilityNodeInfo? =
        runCatching { rootInActiveWindow()?.findFocus(focusType) }.getOrNull()

    override fun performGlobalAction(action: Int): Boolean = when (action) {
        // 返回键在虚拟屏上走 shell 注入；Home/最近任务/通知栏会作用到默认屏，不发
        AccessibilityService.GLOBAL_ACTION_BACK -> XpqVDisplay.injectBack(displayId)
        else -> false
    }

    /** 手势的 displayId 已经固化在 GestureDescription 里，改不了；一律拒绝，避免落到主屏。 */
    override fun dispatchGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?,
    ): Boolean = false

    override fun getServiceInfo(): AccessibilityServiceInfo? = base.getServiceInfo()

    override fun setServiceInfo(info: AccessibilityServiceInfo?) = base.setServiceInfo(info)

    override fun disableSelf() = base.disableSelf()

    override fun inputText(node: AccessibilityNodeInfo?, text: String, clearFirst: Boolean): Boolean =
        base.inputText(node, text, clearFirst)

    override fun inputTextPaste(
        node: AccessibilityNodeInfo?,
        byClipboard: Boolean,
        text: String,
        clearFirst: Boolean,
    ): Boolean = base.inputTextPaste(node, byClipboard, text, clearFirst)

    override fun inputTextNew(node: AccessibilityNodeInfo?, text: String, clearFirst: Boolean): Boolean =
        base.inputTextNew(node, text, clearFirst)

    override fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?) =
        base.setOnAccessibilityEventListener(listener)
}
