package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.ext.utils.LibCtxProvider
import com.google.android.accessibility.ext.vdisplay.XpqVDisplay

private const val TAG = "DisplayScopedDriver"

/** UIAutomation 模式清空输入框时循环发送的 DEL 次数（按屏按键）。 */
private const val CLEAR_DEL_COUNT = 60

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

    /**
     * 手势改走虚拟屏的 shell 注入：GestureDescription 已固化 displayId（改不了），
     * 直接交给主屏的 dispatchGesture 会点到主屏，所以这里把轨迹采样后按屏注入。
     */
    override fun dispatchGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?,
    ): Boolean {
        if (gesture.strokeCount == 0) {
            callback?.onCancelled(gesture)
            return false
        }
        val stroke = gesture.getStroke(0)
        val duration = stroke.duration.coerceIn(1L, 10_000L)
        val pm = PathMeasure(stroke.path, false)
        val len = pm.length
        val xs: IntArray
        val ys: IntArray
        val times: LongArray
        if (len <= 0f) {
            // 点击手势的 path 可能是零长度，取包围盒中心
            val bounds = RectF()
            stroke.path.computeBounds(bounds, false)
            xs = intArrayOf(bounds.centerX().toInt(), bounds.centerX().toInt())
            ys = intArrayOf(bounds.centerY().toInt(), bounds.centerY().toInt())
            times = longArrayOf(0L, duration)
        } else {
            val steps = (duration / 10L).toInt().coerceIn(2, 40)
            xs = IntArray(steps + 1)
            ys = IntArray(steps + 1)
            times = LongArray(steps + 1)
            val pos = FloatArray(2)
            for (j in 0..steps) {
                pm.getPosTan(len * j / steps, pos, null)
                xs[j] = pos[0].toInt()
                ys[j] = pos[1].toInt()
                times[j] = duration * j / steps
            }
        }
        val ok = XpqVDisplay.injectGesture(displayId, xs, ys, times)
        if (ok) callback?.onCompleted(gesture) else callback?.onCancelled(gesture)
        return ok
    }

    override fun getServiceInfo(): AccessibilityServiceInfo? = base.getServiceInfo()

    override fun setServiceInfo(info: AccessibilityServiceInfo?) = base.setServiceInfo(info)

    override fun disableSelf() = base.disableSelf()

    override fun inputText(node: AccessibilityNodeInfo?, text: String, clearFirst: Boolean): Boolean =
        if (mode == EngineMode.UIAUTOMATION) {
            pasteByClipboard(node, text, clearFirst)
        } else {
            setText(node, text, clearFirst) || pasteByClipboard(node, text, clearFirst)
        }

    override fun inputTextPaste(
        node: AccessibilityNodeInfo?,
        byClipboard: Boolean,
        text: String,
        clearFirst: Boolean,
    ): Boolean = if (mode == EngineMode.UIAUTOMATION) {
        pasteByClipboard(node, text, clearFirst)
    } else {
        setText(node, text, clearFirst) || pasteByClipboard(node, text, clearFirst)
    }

    override fun inputTextNew(node: AccessibilityNodeInfo?, text: String, clearFirst: Boolean): Boolean =
        if (mode == EngineMode.UIAUTOMATION) {
            pasteByClipboard(node, text, clearFirst)
        } else {
            setText(node, text, clearFirst) || pasteByClipboard(node, text, clearFirst)
        }

    /**
     * 节点级 SET_TEXT：跨显示有效，不依赖 shell 的默认屏焦点。
     * 只用于无障碍模式（宿主要求 UIAutomation 模式完全不走节点操作）。
     */
    private fun setText(node: AccessibilityNodeInfo?, text: String, clearFirst: Boolean): Boolean {
        node ?: return false
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        if (clearFirst) {
            val empty = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, empty) }
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        Log.i(TAG, "setText($text) -> $ok")
        return ok
    }

    /**
     * UIAutomation 模式的输入：完全不碰节点动作。
     * 按屏点击输入框中心聚焦 → 写系统剪贴板 → 向虚拟屏发 KEYCODE_PASTE(279)。
     */
    private fun pasteByClipboard(node: AccessibilityNodeInfo?, text: String, clearFirst: Boolean): Boolean {
        val ctx = runCatching { LibCtxProvider.appContext }.getOrNull() ?: return false
        // 1. 按屏点击输入框中心（坐标手势，落到虚拟屏，不碰主屏）
        if (node != null) {
            val rect = android.graphics.Rect()
            runCatching { node.getBoundsInScreen(rect) }
            if (rect.width() > 0 && rect.height() > 0) {
                XpqVDisplay.injectTap(displayId, rect.centerX(), rect.centerY())
                Thread.sleep(300)
            }
        }
        // 2. 清空：光标移到末尾后循环删除（按屏按键）
        if (clearFirst) {
            XpqVDisplay.injectKey(displayId, android.view.KeyEvent.KEYCODE_MOVE_END)
            repeat(CLEAR_DEL_COUNT) {
                XpqVDisplay.injectKey(displayId, android.view.KeyEvent.KEYCODE_DEL)
            }
        }
        // 3. 优先走输入法 InputConnection.commitText：不碰节点、不依赖 PASTE，锁屏下也可用。
        //    需要宿主把 App 的输入法设为当前输入法（设置页「输入法保活」）。
        repeat(3) {
            if (com.google.android.accessibility.inputmethod.KeepAliveInputMethod.commitText(text)) {
                Log.i(TAG, "commitTextByIme($text) -> true")
                return true
            }
            Thread.sleep(200)
        }
        // 4. 输入法不可用时退回 shell 剪贴板 + KEYCODE_PASTE
        return runCatching {
            val shellOk = XpqVDisplay.setClipText(text)
            if (!shellOk) {
                val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return false
                cm.setPrimaryClip(ClipData.newPlainText("", text))
            }
            val ok = XpqVDisplay.injectKey(displayId, android.view.KeyEvent.KEYCODE_PASTE)
            Log.i(TAG, "pasteByClipboard($text) -> $ok (shellClip=$shellOk)")
            ok
        }.getOrDefault(false)
    }

    override fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?) =
        base.setOnAccessibilityEventListener(listener)
}
