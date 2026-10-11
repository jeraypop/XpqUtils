package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.ext.utils.LibCtxProvider

/**
 * 代理无障碍服务：UiAutomation 模式下，让全局 accessibilityService 指向本实例，
 * 覆盖非 final 的取根/窗口/焦点方法，委托给 [XpqAcc]（实际路由到 UiAutomation）。
 *
 * 关键：performGlobalAction / dispatchGesture / getServiceInfo / setServiceInfo / disableSelf
 * 是 AccessibilityService 的 final 方法，无法覆盖，由宿主/库内封装点改走 [XpqAcc] 门面。
 * 本实例不会被系统绑定，仅作为「能力代理」被既有扩展函数当作 AccessibilityService 使用，
 * 因此宿主所有 `accessibilityService?.xxx()` 调用零改动即可在 UiAutomation 模式下工作。
 *
 * Context 能力：手动 new 的实例未经系统 attach，`ContextWrapper.mBase` 为 null，于是
 * getResources / getSystemService / getString / getText / getPackageName / registerReceiver /
 * unregisterReceiver / startActivity 等会直接 NPE。[ensureAppContext] 借一次
 * `attachBaseContext(appContext)` 补齐——ContextWrapper 的所有方法都直接读 `mBase` 字段
 * （不走 getBaseContext()），所以一次挂载即全部生效，无需逐个覆盖。
 */
class ProxyAccessibilityService : AccessibilityService() {

    @Volatile
    private var baseContextAttached = false

    /**
     * 幂等：为手动 new 的实例挂上应用 Context，补齐全部 ContextWrapper 能力。
     * 应用 Context 尚未初始化时不置位，下次调用会重试。
     */
    fun ensureAppContext() {
        if (baseContextAttached) return
        val ctx = runCatching { LibCtxProvider.appContext }.getOrNull() ?: return
        runCatching { attachBaseContext(ctx) }
        baseContextAttached = true
    }

    override fun onServiceConnected() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun getRootInActiveWindow(): AccessibilityNodeInfo? = XpqAcc.rootInActiveWindow()

    override fun getWindows(): List<AccessibilityWindowInfo> = XpqAcc.windows() ?: emptyList()

    override fun findFocus(focusType: Int): AccessibilityNodeInfo? = XpqAcc.findFocus(focusType)
}
