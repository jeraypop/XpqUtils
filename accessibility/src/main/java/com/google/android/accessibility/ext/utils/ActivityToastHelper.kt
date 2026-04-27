package com.google.android.accessibility.ext.utils

import android.graphics.Rect
import android.util.DisplayMetrics
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
        marginDp: Int = 25,
        isRight: Boolean = true // true -> 右侧, false -> 左侧
    ): Pair<Int, Int>? {
        node ?: return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return null

        val density = appContext.resources.displayMetrics.density
        val marginPx = (marginDp * density).toInt()

        val x = if (isRight) {
            rect.right - marginPx
        } else {
            rect.left + marginPx
        }

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
    @JvmStatic
    @JvmOverloads
    fun getNodePosition(
        node: AccessibilityNodeInfo?,
        position: NodePosition = NodePosition.RIGHT,
        horizontalMarginDp: Int = 25,
        verticalMarginDp: Int = 0
    ): Pair<Int, Int>? {
        node ?: return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return null

        val density = appContext.resources.displayMetrics.density
        val hMarginPx = (horizontalMarginDp * density).toInt()
        val vMarginPx = (verticalMarginDp * density).toInt()

        // 获取屏幕尺寸
        val metrics: DisplayMetrics = appContext.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val x: Int = when (position) {
            NodePosition.CENTER -> rect.centerX()
            NodePosition.LEFT -> rect.left + hMarginPx
            NodePosition.RIGHT -> rect.right - hMarginPx
            NodePosition.TOP_LEFT, NodePosition.BOTTOM_LEFT -> rect.left + hMarginPx
            NodePosition.TOP_RIGHT, NodePosition.BOTTOM_RIGHT -> rect.right - hMarginPx
            NodePosition.TOP, NodePosition.BOTTOM -> rect.centerX()
        }

        val y: Int = when (position) {
            NodePosition.CENTER -> rect.centerY()
            NodePosition.TOP -> rect.top + vMarginPx
            NodePosition.BOTTOM -> rect.bottom - vMarginPx
            NodePosition.TOP_LEFT, NodePosition.TOP_RIGHT -> rect.top + vMarginPx
            NodePosition.BOTTOM_LEFT, NodePosition.BOTTOM_RIGHT -> rect.bottom - vMarginPx
            NodePosition.LEFT, NodePosition.RIGHT -> rect.centerY()
        }

        // 限制在屏幕内
        val finalX = x.coerceIn(0, screenWidth)
        val finalY = y.coerceIn(0, screenHeight)

        return Pair(finalX, finalY)
    }

}

enum class NodePosition {
    CENTER,
    LEFT, RIGHT,
    TOP, BOTTOM,
    TOP_LEFT, TOP_RIGHT,
    BOTTOM_LEFT, BOTTOM_RIGHT
}