package com.google.android.accessibility.ext.utils

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/10/6  22:03
 * Description:This is RecentsUtils
 */
object RecentsUtils {
    private const val TAG = "RecentsUtils"

    // 线程安全的集合，保存“永远隐藏”的 Activity 全限定名
    private val alwaysHiddenActivities = CopyOnWriteArraySet<String>()

    /**
     * 设置最近任务显示状态。
     *
     * 规则：
     * - 如果 targetActivities 非空：这些 Activity 将被 **加入永远隐藏集合**（alwaysHidden），
     *   并 **始终以隐藏（exclude=true）方式处理**（即不允许通过 exclude=false 恢复）。
     * - 如果 targetActivities 为空：按 exclude 参数对匹配到的任务做临时 hide/unhide（不修改 alwaysHidden 集合）。
     *
     * 返回：被操作的 task 数量（attempted count）。
     */
    @JvmOverloads
    @JvmStatic
    fun setExcludeFromRecents(
        exclude: Boolean,
        targetActivities: Collection<String> = emptyList()
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            //Log.w(TAG, "API < 24 not supported in this method")
            return 0
        }

        // 如果传了目标列表，把它们加入永远隐藏集合，并强制把 exclude 视为 true
        val effectiveTargets = if (targetActivities.isNotEmpty()) {
            // 将这些活动标记为 always-hidden
            targetActivities.forEach { alwaysHiddenActivities.add(it) }
            // we will treat them as targets to exclude (ignore exclude=false)
            targetActivities
        } else {
            // 没传目标，默认使用 alwaysHidden 集合作为额外的目标（始终隐藏）
            emptyList<String>()
        }

        try {
            val ctx = appContext ?: return 0
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0
            val tasks = am.appTasks ?: return 0

            var count = 0
            tasks.forEach { appTask ->
                try {
                    val info = appTask.taskInfo
                    val base = info?.baseActivity?.className
                    val top = info?.topActivity?.className

                    // 如果 targetActivities 非空：只匹配这些并强制隐藏
                    if (effectiveTargets.isNotEmpty()) {
                        if (base in effectiveTargets || top in effectiveTargets) {
                            appTask.setExcludeFromRecents(true) // 强制隐藏
                            count++
                        }
                        return@forEach
                    }

                    // targetActivities 为空：按 exclude 来决定，但始终对 alwaysHiddenActivities 生效（强制隐藏）
                    val shouldForceHide = base in alwaysHiddenActivities || top in alwaysHiddenActivities
                    val shouldApply = when {
                        shouldForceHide -> true // always-hidden 优先
                        base != null && top != null -> true // 如果没有特定 targets，允许对所有任务应用 exclude
                        else -> true
                    }

                    if (shouldApply) {
                        // 如果这个 task 包含 alwaysHidden 的 activity，则强制隐藏（ignore exclude param）
                        if (shouldForceHide) {
                            appTask.setExcludeFromRecents(true)
                        } else {
                            appTask.setExcludeFromRecents(exclude)
                        }
                        count++
                    }
                } catch (e: Exception) {
                    //Log.w(TAG, "setExcludeFromRecents on one task failed", e)
                }
            }

            //Log.d(TAG, "setExcludeFromRecents(effectiveTargets=${effectiveTargets.size}, exclude=$exclude) applied to $count tasks")
            return count
        } catch (e: Exception) {
            //Log.e(TAG, "setExcludeFromRecents failed", e)
            return 0
        }
    }

    /**
     * 在 Activity 实例内精确匹配该 Activity 所在的 AppTask 并设置 excludeFromRecents。
     *
     * 行为：
     * - 如果该 Activity 在 alwaysHiddenActivities 中：始终以隐藏(true) 应用（并返回 true）。
     * - 否则按传入的 exclude 应用（返回是否找到并应用）。
     */
    @JvmStatic
    fun excludeTaskForActivityInstance(activity: Activity, exclude: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        try {
            val ctx = appContext ?: return false
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
            val tasks = am.appTasks ?: return false

            val myClassName = activity::class.qualifiedName ?: activity::class.java.name

            val forceHide = myClassName in alwaysHiddenActivities

            tasks.forEach { appTask ->
                try {
                    val info = appTask.taskInfo
                    val base = info?.baseActivity
                    val top = info?.topActivity
                    if (base?.className == myClassName || top?.className == myClassName) {
                        if (forceHide) {
                            appTask.setExcludeFromRecents(true)
                            //Log.d(TAG, "Force-hidden alwaysHidden activity task: $myClassName")
                        } else {
                            appTask.setExcludeFromRecents(exclude)
                            //Log.d(TAG, "Applied setExcludeFromRecents($exclude) to task for $myClassName")
                        }
                        return true
                    }
                } catch (t: Throwable) {
                    //Log.w(TAG, "Error checking appTask", t)
                }
            }

            //Log.w(TAG, "No matching AppTask found for $myClassName")
            return false
        } catch (e: Exception) {
            //Log.e(TAG, "excludeTaskForActivityInstance failed", e)
            return false
        }
    }

    /**
     * 强制保证某 Activity 永远隐藏（用于 Application 启动时配置或运行时添加）。
     *
     * 实现：把 className 加入 alwaysHiddenActivities，然后进行 N 次重试尝试隐藏对应任务（3 次，每次 250ms）。
     */
    @JvmStatic
    fun enforceAlwaysHiddenForActivity(className: String, attempts: Int = 3, delayMs: Long = 250L) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            //Log.w(TAG, "API < 24 not supported in enforceAlwaysHiddenForActivity")
            return
        }
        alwaysHiddenActivities.add(className)

        // 在主线程的 Handler 上做重试（避免直接在子线程操作 UI 相关系统调用）
        val handler = Handler(Looper.getMainLooper())
        for (i in 0 until attempts) {
            handler.postDelayed({
                try {
                    // 每次都调用针对目标的排除（强制隐藏）
                    setExcludeFromRecents(true, listOf(className))
                    //Log.d(TAG, "enforceAlwaysHiddenForActivity: attempted hide for $className (attempt ${i + 1})")
                } catch (t: Throwable) {
                    //Log.w(TAG, "enforceAlwaysHiddenForActivity attempt ${i + 1} failed for $className", t)
                }
            }, delayMs * i)
        }
    }

    /**
     * 移除某个 className 的永久隐藏策略（如果需要撤销）。
     * 注意：撤销后如果该 task 目前被隐藏，需要额外调用 setExcludeFromRecents(false, listOf(className))
     * 来尝试恢复其在 Recents 的显示（如果系统允许）。
     */
    @JvmStatic
    fun removeAlwaysHidden(className: String) {
        alwaysHiddenActivities.remove(className)
        //Log.d(TAG, "removeAlwaysHidden: $className removed from alwaysHiddenActivities")
    }

    /**
     * 检查某 activity 是否被标记为 always hidden。
     */
    @JvmStatic
    fun isAlwaysHidden(className: String): Boolean = className in alwaysHiddenActivities
}

