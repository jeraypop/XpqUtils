package com.google.android.accessibility.ext.utils

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.android.accessibility.ext.utils.FloatToastManager.showFloatToast
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getShowClickIndicator
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/4/25  12:35
 * Description:This is ActivityToastHelper
 */
object ActivityToastHelper {

    // Activity -> 描述 映射表
    private val activityMap = mapOf(
        // 朋友圈
        "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI" to "进入朋友圈",
        "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI" to "进入朋友圈",
        "com.tencent.mm.plugin.sns.ui.SnsUserUI" to "进入朋友圈",

        // 公众号
        "com.tencent.mm.plugin.brandservice.ui.timeline.preload.ui.TmplWebViewMMUI" to "进入公众号",
        "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI" to "进入公众号"
    )

    @JvmStatic
    @JvmOverloads
    fun showActivityToast(currentActivity: String?, appName: String = "微信",pkgName: String = "com.tencent.mm") {
        if (currentActivity.isNullOrEmpty()) return

        val description = activityMap[currentActivity]
        if (!description.isNullOrEmpty()) {
            if (getShowClickIndicator()){
                showFloatToast(message = "$appName :$description", packageName = pkgName)
            }else{
                AliveUtils.toast(msg = "$appName :$description")
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getNodeRightMarginDp(
        node: AccessibilityNodeInfo?,
        marginDp: Int = 25
    ): Pair<Int, Int>? {
        if (node == null) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return null

        // dp 转 px
        val density = appContext.resources.displayMetrics.density
        val marginPx = (marginDp * density).toInt()

        val x = rect.right - marginPx
        val y = rect.centerY()

        return Pair(x, y)
    }


    /**
     * 提取字符串中所有数字并拼接成一个整数
     * 没有数字时返回 defaultValue
     */
    @JvmStatic
    @JvmOverloads
    fun extractAllNumbers(str: String?, defaultValue: Int = 25): Int {
        if (str.isNullOrEmpty()) return defaultValue

        val numberStr = str.filter { it.isDigit() }
        return numberStr.toIntOrNull() ?: defaultValue
    }

}