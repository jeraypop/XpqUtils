package com.google.android.accessibility.ext.utils.verificationcode

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.ext.acc.inputTextPaste
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.copyToClipboard
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.getNodeInfo
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.recycleCompat
import com.google.android.accessibility.selecttospeak.accessibilityService

/**
同一个验证码
只填充一次
即使 OtpCenter 出问题
 * Description:This is AutoFillManager
 */
object AutoFillManager {

    private const val FILL_WINDOW = 5000L

    private var lastCode = ""

    private var lastFillTime = 0L

    fun shouldFill(
        code: String
    ): Boolean {

        val now = System.currentTimeMillis()

        if (
            code == lastCode &&
            now - lastFillTime < FILL_WINDOW
        ) {
            return false
        }

        lastCode = code
        lastFillTime = now

        return true
    }
    fun autoFill(
        code: String,
        accService: AccessibilityService? = accessibilityService,
        byClipboard: Boolean = false
    ) {
        accService ?: return
        //自动填充开关
        if (!LoginConfig.isAutoFillEnabled()) {
            return
        }
        if (!shouldFill(code)) {
            return
        }
        //复制到剪贴板
        copyToClipboard(text = code)
        //语音播报开关
        if (LoginConfig.isVoiceReadEnabled()) {
            LoginConfig.playYanZhenMa(code)
        }
        val accessibilityNodeInfo = getNodeInfo(accService.rootInActiveWindow, code)
        accessibilityNodeInfo ?: return
        //节点文本不为空
        accessibilityNodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val parent = accessibilityNodeInfo.parent
        if (parent != null) {
            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        accessibilityNodeInfo.inputTextPaste(byClipboard,code)
        recycleCompat(accessibilityNodeInfo)

    }



}