package com.google.android.accessibility.ext.utils

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/1/14
 * Description: ScrollUntilEndTask
 *
 * 连续滑动列表直到真正到底（抗抖动 / 可取消 / 防泄漏）
 */

/**
 * 列表尾部指纹
 * 用于判断“内容是否真的发生变化”
 */
data class ListFingerprint(
    val lastId: String?,
    val lastText: CharSequence?,
    val lastBottom: Int
)

class ScrollUntilEndTask {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    /**
     * 主动取消任务（如 window 切换 / 新任务启动）
     */
    fun cancel(reason: String = "cancel") {
        //Log.d("ScrollUntilEndTask", "cancel: $reason")
        finishInternal()
    }


    /**
     * 启动滑动任务
     *
     * @param list 列表节点
     * @param maxScrollTimes 最大滑动次数上限
     * @param delayMs 每次滑动后等待 UI 稳定的时间
     * @param stableThreshold 连续多少次“尾部不变”才算真正到底
     * @param onEachScroll 每次滑动后的回调
     * @param onFinish 最终完成回调（true=到底 / false=被中断或到达上限）
     */
    fun start(
        list: AccessibilityNodeInfo,
        maxScrollTimes: Int = 20,
        delayMs: Long = 300,
        stableThreshold: Int = 3,
        onEachScroll: ((index: Int) -> Unit)? = null,
        onFinish: (reachedEnd: Boolean) -> Unit
    ) {
        // 🚫 非列表直接结束
        if (!isListLike(list)) {
            finish(true, onFinish)
            return
        }

        var scrollCount = 0
        var stableCount = 0
        var lastFingerprint: ListFingerprint? = null

        fun step() {
            if (finished) return

            // ⛔ 超出最大滑动次数
            if (scrollCount >= maxScrollTimes) {
                finish(false, onFinish)
                return
            }

            scrollOnceAndCheckChanged(list, delayMs) { changed ->
                if (finished) return@scrollOnceAndCheckChanged

                val current = buildListFingerprint(list)

                if (!changed && current == lastFingerprint) {
                    stableCount++
                } else {
                    stableCount = 0
                }

                lastFingerprint = current
                scrollCount++
                onEachScroll?.invoke(scrollCount)

                // ✅ 连续 N 次内容不再变化 → 真正到底
                if (stableCount >= stableThreshold) {
                    finish(true, onFinish)
                } else {
                    handler.post { step() }
                }
            }
        }

        step()
    }

    /**
     * 真正完成任务（统一出口）
     */
    private fun finish(
        reachedEnd: Boolean,
        onFinish: (Boolean) -> Unit
    ) {
        if (finished) return
        finished = true
        onFinish(reachedEnd)
        finishInternal()
    }

    /**
     * 内部清理（防泄漏）
     */
    private fun finishInternal() {
        finished = true
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 判断是否为“列表型节点”
     */
    fun isListLike(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val className = node.className?.toString() ?: ""

        // 1️⃣ 明确的系统列表控件
        if (className == "android.widget.ListView" ||
            className == "android.widget.GridView" ||
            className == "androidx.recyclerview.widget.RecyclerView"
        ) {
            return true
        }

        // 2️⃣ 无障碍集合语义（非常关键）
        if (node.collectionInfo != null) {
            return true
        }

        // 3️⃣ 兜底：可滚动 + 多子节点
        if (node.isScrollable && node.childCount >= 2) {
            return true
        }

        return false
    }

    /**
     * 构建“列表尾部指纹”
     */
    fun buildListFingerprint(list: AccessibilityNodeInfo): ListFingerprint? {
        val count = list.childCount
        if (count <= 0) return null

        val last = list.getChild(count - 1) ?: return null

        val rect = Rect()
        last.getBoundsInScreen(rect)

        return ListFingerprint(
            last.viewIdResourceName,
            last.text,
            rect.bottom
        )
    }

    /**
     * 单次滑动 + 判断内容是否变化
     */
    private fun scrollOnceAndCheckChanged(
        list: AccessibilityNodeInfo,
        delayMs: Long,
        callback: (changed: Boolean) -> Unit
    ) {
        val before = buildListFingerprint(list)

        val scrolled = list.performAction(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )

        if (!scrolled) {
            callback(false)
            return
        }

        // ✅ 使用同一个 handler，支持 cancel
        handler.postDelayed({
            if (finished) return@postDelayed
            val after = buildListFingerprint(list)
            callback(before != after)
        }, delayMs)
    }
}
