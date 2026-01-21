package tang.bo.hu.qwhb.utilsQW

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.collections.isNullOrEmpty

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/1/15  16:30
 * Description:This is AccessibilityNodeInfoXPQUtil
 */

/**
 * 从当前节点开始，向上查找指定 ViewId
 * @param maxDepth 最大向上查找层级，防止死循环
 */
fun AccessibilityNodeInfo.findUpwardByViewId(
    viewId: String,
    maxDepth: Int = 3
): List<AccessibilityNodeInfo> {
    var current: AccessibilityNodeInfo? = this
    var depth = 0

    while (current != null && depth <= maxDepth) {
        val result = current.findAccessibilityNodeInfosByViewId(viewId)
        if (!result.isNullOrEmpty()) {
            return result
        }
        current = current.parent
        depth++
    }
    return emptyList()
}

/**
 * 查找指定 ViewId，支持有限次数重试
 *
 * @param root 根节点
 * @param viewId 要查找的 ViewId
 * @param retryTimes 重试次数
 * @param retryDelayMs 每次重试间隔（同步版本不 sleep）
 */
fun AccessibilityNodeInfo?.findByViewIdWithRetry(
    viewId: String,
    retryTimes: Int = 5
): List<AccessibilityNodeInfo> {

    val node = this ?: return emptyList()

    repeat(retryTimes) {
        try {
            val result = node.findAccessibilityNodeInfosByViewId(viewId)
            if (result.isNotEmpty()) {
                return result
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }
    return emptyList()
}

