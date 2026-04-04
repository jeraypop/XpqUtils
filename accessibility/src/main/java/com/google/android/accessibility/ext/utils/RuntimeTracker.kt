package com.google.android.accessibility.ext.utils


import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

object RuntimeTracker : Application.ActivityLifecycleCallbacks {

    private const val SP_NAME = "runtime_tracker"

    private const val KEY_PROCESS_START = "process_start"
    private const val KEY_TOTAL = "total_runtime"

    private const val KEY_FOREGROUND_TOTAL = "fg_total"
    private const val KEY_BACKGROUND_TOTAL = "bg_total"

    private const val KEY_LAST_SWITCH = "last_switch_time"
    private const val KEY_IS_FOREGROUND = "is_foreground"

    private const val MAX_DURATION = 7 * 24 * 60 * 60 * 1000L

    private var startedActivityCount = 0

    /**
     * 初始化（建议 Application 或 ContentProvider）
     */
    fun init(app: Application) {
        val sp = app.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()

        val lastProcessStart = sp.getLong(KEY_PROCESS_START, 0L)
        val lastSwitch = sp.getLong(KEY_LAST_SWITCH, 0L)
        val wasForeground = sp.getBoolean(KEY_IS_FOREGROUND, false)

        // ===== 1. 补偿进程运行时间 =====
        if (lastProcessStart > 0 && now > lastProcessStart) {
            val duration = now - lastProcessStart
            if (isValid(duration)) {
                val total = sp.getLong(KEY_TOTAL, 0L)
                sp.edit().putLong(KEY_TOTAL, total + duration).apply()
            }
        }

        // ===== 2. 补偿前后台时间 =====
        if (lastSwitch > 0 && now > lastSwitch) {
            val duration = now - lastSwitch
            if (isValid(duration)) {
                if (wasForeground) {
                    val fg = sp.getLong(KEY_FOREGROUND_TOTAL, 0L)
                    sp.edit().putLong(KEY_FOREGROUND_TOTAL, fg + duration).apply()
                } else {
                    val bg = sp.getLong(KEY_BACKGROUND_TOTAL, 0L)
                    sp.edit().putLong(KEY_BACKGROUND_TOTAL, bg + duration).apply()
                }
            }
        }

        // ===== 3. 重置状态 =====
        sp.edit()
            .putLong(KEY_PROCESS_START, now)
            .putLong(KEY_LAST_SWITCH, now)
            .putBoolean(KEY_IS_FOREGROUND, false)
            .apply()

        // ===== 4. 注册生命周期 =====
        app.registerActivityLifecycleCallbacks(this)
    }

    // ================= 生命周期监听 =================

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++

        if (startedActivityCount == 1) {
            // 进入前台
            switchState(activity, true)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--

        if (startedActivityCount == 0) {
            // 进入后台
            switchState(activity, false)
        }
    }

    private fun switchState(context: Context  = appContext, toForeground: Boolean) {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

        val now = SystemClock.elapsedRealtime()
        val lastSwitch = sp.getLong(KEY_LAST_SWITCH, 0L)
        val wasForeground = sp.getBoolean(KEY_IS_FOREGROUND, false)

        if (lastSwitch > 0 && now > lastSwitch && isValid(now - lastSwitch)) {
            val duration = now - lastSwitch

            if (wasForeground) {
                val fg = sp.getLong(KEY_FOREGROUND_TOTAL, 0L)
                sp.edit().putLong(KEY_FOREGROUND_TOTAL, fg + duration).apply()
            } else {
                val bg = sp.getLong(KEY_BACKGROUND_TOTAL, 0L)
                sp.edit().putLong(KEY_BACKGROUND_TOTAL, bg + duration).apply()
            }
        }

        sp.edit()
            .putLong(KEY_LAST_SWITCH, now)
            .putBoolean(KEY_IS_FOREGROUND, toForeground)
            .apply()
    }

    // ================= 对外API =================
    @JvmStatic
    fun formatDurationWithSeconds(ms: Long): String {
        if (ms <= 0) return "0秒"

        val totalSeconds = ms / 1000

        val days = totalSeconds / (24 * 3600)
        val hours = (totalSeconds % (24 * 3600)) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            days > 0 -> "${days}天${hours}小时${minutes}分${seconds}秒"
            hours > 0 -> "${hours}小时${minutes}分${seconds}秒"
            minutes > 0 -> "${minutes}分${seconds}秒"
            else -> "${seconds}秒"
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getProcessRuntime(context: Context = appContext): Long {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val start = sp.getLong(KEY_PROCESS_START, 0L)
        return if (start > 0) SystemClock.elapsedRealtime() - start else 0L
    }
    @JvmStatic
    @JvmOverloads
    fun getForegroundTime(context: Context = appContext): Long {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val base = sp.getLong(KEY_FOREGROUND_TOTAL, 0L)

        return if (isCurrentlyForeground(context)) {
            base + getSinceLastSwitch(context)
        } else base
    }
    @JvmStatic
    @JvmOverloads
    fun getBackgroundTime(context: Context = appContext): Long {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val base = sp.getLong(KEY_BACKGROUND_TOTAL, 0L)

        return if (!isCurrentlyForeground(context)) {
            base + getSinceLastSwitch(context)
        } else base
    }
    @JvmStatic
    @JvmOverloads
    private fun getSinceLastSwitch(context: Context = appContext): Long {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_LAST_SWITCH, 0L)
        val now = SystemClock.elapsedRealtime()
        return if (last > 0 && now > last) now - last else 0L
    }
    @JvmStatic
    @JvmOverloads
    private fun isCurrentlyForeground(context: Context = appContext): Boolean {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_FOREGROUND, false)
    }
    @JvmStatic
    @JvmOverloads
    fun isValid(duration: Long): Boolean {
        return duration in 0..MAX_DURATION
    }

    // unused lifecycle
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}