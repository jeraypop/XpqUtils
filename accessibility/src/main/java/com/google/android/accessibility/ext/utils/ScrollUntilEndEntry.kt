package com.google.android.accessibility.ext.utils

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/1/14  15:33
 * Description:This is ScrollUntilEndEntry
 */
object ScrollUntilEndEntry {

    private const val DEFAULT_DEBOUNCE_MS = 1000L

    private val handler = Handler(Looper.getMainLooper())

    private var pendingRunnable: Runnable? = null
    private var currentTask: ScrollUntilEndTask? = null

    /**
     * 对外唯一入口（带防抖）
     */
    @JvmOverloads
    @JvmStatic
    fun start(
        list: AccessibilityNodeInfo,
        debounceMs: Long = DEFAULT_DEBOUNCE_MS,
        maxScrollTimes: Int = 20,
        delayMs: Long = 300,
        stableThreshold: Int = 3,
        onEachScroll: ((index: Int) -> Unit)? = null,
        onFinish: ((reachedEnd: Boolean) -> Unit)? = null
    ) {
        // 1️⃣ 取消尚未触发的防抖任务
        pendingRunnable?.let {
            handler.removeCallbacks(it)
        }

        val runnable = Runnable {
            // 2️⃣ cancel 上一次真正执行的滚动任务
            currentTask?.cancel("new debounced start")

            // 3️⃣ 启动新任务
            val task = ScrollUntilEndTask()
            currentTask = task

            task.start(
                list = list,
                maxScrollTimes = maxScrollTimes,
                delayMs = delayMs,
                stableThreshold = stableThreshold,
                onEachScroll = onEachScroll,
                onFinish = { reachedEnd ->
                    // 防止旧任务回调污染
                    if (currentTask === task) {
                        onFinish?.invoke(reachedEnd)
                        currentTask = null
                    }
                }
            )
        }

        pendingRunnable = runnable
        handler.postDelayed(runnable, debounceMs)
    }

    /**
     * 主动取消（可选）
     */
    @JvmOverloads
    @JvmStatic
    fun cancel(reason: String = "manual cancel") {
        pendingRunnable?.let {
            handler.removeCallbacks(it)
            pendingRunnable = null
        }
        currentTask?.cancel(reason)
        currentTask = null
    }
    @JvmOverloads
    @JvmStatic
    fun findTargetList(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        fun dfs(node: AccessibilityNodeInfo) {
            // 剪枝
            if (node.childCount <= 0) return

            val score = scoreListCandidate(node)
            if (score > bestScore) {
                bestScore = score
                best = node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { dfs(it) }
            }
        }

        dfs(root)
        return best
    }

     fun scoreListCandidate(node: AccessibilityNodeInfo): Int {
        var score = 0

        val className = node.className?.toString() ?: ""

        // 1️⃣ 明确的系统列表（权重最高）
        when (className) {
            "android.widget.ListView",
            "android.widget.GridView",
            "androidx.recyclerview.widget.RecyclerView"
                -> score += 100
        }

        // 2️⃣ 无障碍集合语义（非常关键）
        node.collectionInfo?.let {
            score += 80
            if (it.rowCount > 1 || it.columnCount > 1) {
                score += 10
            }
        }

        // 3️⃣ 可滚动能力
        if (node.isScrollable) {
            score += 30
        }

        // 4️⃣ 子节点数量（排除 item）
        val childCount = node.childCount
        if (childCount >= 5) score += 20
        else if (childCount >= 2) score += 10

        // 5️⃣ 排除明显不是列表的控件
        if (childCount <= 1) score -= 20

        return score
    }



}
