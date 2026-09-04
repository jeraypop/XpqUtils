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
     * 文本输入原子能力：把 [text] 写入 [node] 命中的输入框。
     * 无障碍通道 = FOCUS + ACTION_SET_TEXT；UiAutomation 通道 = 点击聚焦后走 shell 输入/粘贴。
     * 与 [dispatchGesture] 一样由 [XpqAcc] 门面按当前通道分流。
     */
    fun inputText(node: AccessibilityNodeInfo?, text: String): Boolean

    /**
     * 粘贴式输入：无障碍通道 = 清空后 FOCUS + PASTE（或按 scheme 走 SET_TEXT）；
     * UiAutomation 通道 = 点击聚焦后走剪贴板粘贴。
     */
    fun inputTextPaste(node: AccessibilityNodeInfo?, byClipboard: Boolean, text: String): Boolean

    /**
     * 延迟输入：无障碍通道 = sleep 后 SET_TEXT；UiAutomation 通道 = 点击聚焦后走 shell 注入。
     */
    fun inputTextNew(node: AccessibilityNodeInfo?, text: String): Boolean

    /**
     * 事件桥接：UiAutomation 通道把无障碍事件转发到宿主回调；
     * 无障碍通道由系统直接回调 SelectToSpeakServiceAbstract，此方法留空。
     */
    fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?)
}

enum class EngineMode { ACCESSIBILITY_SERVICE, UIAUTOMATION }

/**
 * UiAutomation 模式下输入文本的底层策略（仅 UiAutomation 通道有意义；无障碍通道固定用 ACTION_SET_TEXT）。
 * 两种方案均先点击输入框获取 IME 焦点，再注入文本，用于测试后选定最终方案。
 */
enum class InputTextStrategy {
    /** 剪贴板 + shell `input keyevent 279`(KEYCODE_PASTE)：支持中文/任意字符。 */
    CLIPBOARD_PASTE,

    /** shell `input text`：最简单，但仅支持 ASCII（中文会失败，空格需转义 %s）。 */
    SHELL_INPUT_TEXT,
}
