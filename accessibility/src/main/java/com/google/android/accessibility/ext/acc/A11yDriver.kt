package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.copyToClipboard
import com.google.android.accessibility.ext.utils.gestureUtils.HumanTouchEngine
import com.google.android.accessibility.ext.utils.verificationcode.LoginConfig
import com.google.android.accessibility.inputmethod.KeepAliveInputMethod
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlin.random.Random

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

    override fun inputText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (KeepAliveInputMethod.imeIsActive != null){
            imeInput(node,text)
            return true
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        //node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun inputTextPaste(node: AccessibilityNodeInfo?, byClipboard: Boolean, text: String): Boolean {
        if (node == null) return false
        if (KeepAliveInputMethod.imeIsActive != null){
            imeInput(node,text)
            return true
        }
        val bundle = Bundle()
        // 已粘贴过或输入框已有内容时先清空
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        // 复制到系统剪贴板（byClipboard=false 时原逻辑也统一复制，保持一致）
        copyToClipboard(text = text)
        return when (LoginConfig.getScheme()) {
            1 -> {
                // 方案1：焦点 + 粘贴系统剪贴板
                //node.performAction(AccessibilityNodeInfo.FOCUS_INPUT)
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            else -> {
                // 方案2 及默认：bundle 携带文本 SET_TEXT
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            }
        }
    }

    override fun inputTextNew(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        SystemClock.sleep(250 + Random.nextLong(200)) // 250~450ms 随机
        if (KeepAliveInputMethod.imeIsActive != null){
            imeInput(node,text)
            return true
        }else{
            sendLog("通过无障碍粘贴文字，很容易被检测到，推荐开启 输入法保活 这个设置")
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?) {
        // 无障碍通道由系统直接回调 SelectToSpeakServiceAbstract.onAccessibilityEvent，无需桥接
    }

    fun imeInput(node: AccessibilityNodeInfo?, str: String){
        if (node == null) return
        accessibilityService?.gestureClick(node)
        KeepAliveInputMethod.commitText(str)
        sendLog("通过输入法输入文本: $str")
        Log.e("输入法输入", "imeInput: ")
    }
}
