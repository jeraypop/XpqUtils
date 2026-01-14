package com.google.android.accessibility.ext.utils

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/1/14  15:01
 * Description:This is ScrollUntilEndTask
 */
data class ListFingerprint(
    val lastId: String?,
    val lastText: CharSequence?,
    val lastBottom: Int
)
class ScrollUntilEndTask {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    fun cancel() {
        finishInternal()
    }
    /*
 *  maxScrollTimes 最大滑动次数上限
 * delayMs: Long = 300 一次滑动后，等待 UI 刷新的时间
 * stableThreshold: Int = 2 “连续多少次内容不再变化” 才认为滑动到底
 * onEachScroll  每次成功滑动后的回调
 * onFinish  整个滑动流程结束时的最终回调
 * */
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
            onFinish(true)
            finishInternal()
            return
        }

        var scrollCount = 0
        var stableCount = 0
        var lastFingerprint: ListFingerprint? = null

        fun finish(reachedEnd: Boolean) {
            if (finished) return
            finished = true
            onFinish(reachedEnd)
            finishInternal()
        }

        fun step() {
            if (finished) return

            if (scrollCount >= maxScrollTimes) {
                finish(false)
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

                if (stableCount >= stableThreshold) {
                    finish(true)
                } else {
                    handler.post { step() }
                }
            }
        }

        step()
    }

    private fun finishInternal() {
        finished = true
        handler.removeCallbacksAndMessages(null)
    }

    fun isListLike(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val className = node.className?.toString() ?: ""

        // 1️⃣ 明确的系统列表控件（最稳）
        if (className == "android.widget.ListView" ||
            className == "android.widget.GridView" ||
            className == "androidx.recyclerview.widget.RecyclerView"
        ) {
            return true
        }

        // 2️⃣ 无障碍语义集合（非常关键）
        if (node.collectionInfo != null) {
            return true
        }

        // 3️⃣ 兜底：可滚动 + 有多个子节点
        if (node.isScrollable && node.childCount >= 2) {
            return true
        }

        return false
    }
    //    2️⃣ 构建指纹
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

    //3️⃣ 单步滑动 + 是否变化
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

        Handler(Looper.getMainLooper()).postDelayed({
            val after = buildListFingerprint(list)
            callback(before != after)
        }, delayMs)
    }



}
