package com.google.android.accessibility.uiautomation.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.ShizukuProvider

/**
 * `rikka.shizuku.ShizukuProvider` 组件自愈。
 *
 * ## 为什么需要它
 *
 * 上层「Shizuku 未运行」这句话是 `Shizuku.pingBinder() == false` 的同义词，而它的成因**不止一个**。
 * 其中一个极隐蔽、且文案完全指不出来的成因是：**该 provider 组件被外部设成了 DISABLED**
 * （手动 `adb shell pm disable <pkg>/rikka.shizuku.ShizukuProvider`、组件管理/精简类工具）。
 * 此时：
 *
 * ```
 * 组件被禁
 *   → AMS 启动进程时不实例化该 provider（清单解析层照样有，容易误判为"声明没问题"）
 *   → Shizuku server 的 ActivityManagerApis.getContentProviderExternal("<pkg>.shizuku") 返回 null
 *      （server 只打一行 `E/Service: provider is null <pkg>.shizuku 0` 就放弃，不重试、不报错）
 *   → Shizuku.binder 恒为 null
 *   → Shizuku.pingBinder() 恒为 false
 *   → 上层只能报「Shizuku 未运行」
 * ```
 *
 * 实测判定命令（组件被禁时 `dumpsys package` 的 `ContentProvider Authorities` 段**照样列出**该 provider，
 * 不能用来判断；必须看运行时发布情况）：
 *
 * ```bash
 * adb shell dumpsys package <pkg> | grep -A3 disabledComponents     # 第一现场
 * adb shell dumpsys activity providers | grep -i '<pkg>.shizuku'    # 有无运行时 ContentProviderRecord
 * ```
 *
 * ## 使用方式
 *
 * 在 `Application.onCreate()` **最前面**调用一次（越早越好）：
 *
 * ```kotlin
 * if (ShizukuProviderGuard.ensureEnabled(this)) {
 *     // 组件刚被恢复：本次进程启动时它是被禁的，server 那一轮 binder 投递已经失败，
 *     // 见下方「关于生效时机」，按你的产品形态二选一处理。
 * }
 * ```
 *
 * ## 关于生效时机（实测结论）
 *
 * - 组件改回 ENABLED 后，AMS 会重新发布该 provider（实测 `dumpsys activity providers` 立即出现、
 *   `adb shell content call --uri content://<pkg>.shizuku --method getBinder` 当场能返回 binder）。
 * - 但 **binder 只能由 Shizuku server 主动 `sendBinder` 投递，App 侧无法拉取**：
 *   `getBinder` 只是把「已经存下的」`Shizuku.binder` 返回给同 App 的其他进程，拿不到就返回 null。
 * - server 的投递触发点是【进程启动 / uid cached 变化 / 应用前后台切换】，都是 App 无法直接触发的。
 *
 * 因此 `ensureEnabled()` 返回 true（=刚做过修复）时，**本进程这次一定还是拿不到 binder**：
 * - 产品形态是「用户手动打开」：提示一句大白话「已自动修复，请重新打开应用」，用户下次进前台时
 *   server 会重新投递，自然就好；
 * - 产品形态是「后台常驻」：可选自动重来一次（`AlarmManager` 预约启动 + 结束本进程），
 *   见 [restartProcessSoon]。
 */
object ShizukuProviderGuard {

    private const val TAG = "ShizukuProviderGuard"

    /**
     * 组件是否被显式关掉（DISABLED / DISABLED_USER / DISABLED_UNTIL_USED）。
     *
     * 注意：返回 false 只代表"没被显式关掉"（DEFAULT 或 ENABLED），不代表组件在清单里存在。
     */
    fun isDisabled(context: Context): Boolean = try {
        when (context.packageManager.getComponentEnabledSetting(component(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            else -> false
        }
    } catch (t: Throwable) {
        Log.e(TAG, "读取 ShizukuProvider 组件状态失败：${t.javaClass.name}: ${t.message}", t)
        false
    }

    /**
     * 检查并按需把 ShizukuProvider 组件恢复为 ENABLED。
     *
     * 用 `DONT_KILL_APP`，不会因此杀掉当前进程（App 改自己的组件不需要任何权限）。
     *
     * @param onLog 便于宿主接入自己的日志系统（本方法也会同时写 logcat，tag = `ShizukuProviderGuard`）。
     * @return **true 表示本次真的做了修复**（即调用前组件处于禁用态）。此时本进程的
     *         `Shizuku.pingBinder()` 仍然是 false，需要按 [ShizukuProviderGuard] 类注释里的
     *         「关于生效时机」处理，不要把它当成用户没开 Shizuku。
     */
    @JvmStatic
    @JvmOverloads
    fun ensureEnabled(context: Context, onLog: (String) -> Unit = {}): Boolean {
        val cn = component(context)
        val pm = context.packageManager

        val state = try {
            pm.getComponentEnabledSetting(cn)
        } catch (t: Throwable) {
            Log.e(TAG, "读取 ShizukuProvider 组件状态失败：${t.javaClass.name}: ${t.message}", t)
            onLog("✗ 读取 ShizukuProvider 组件状态失败：${t.javaClass.name}: ${t.message}")
            return false
        }

        if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        ) {
            return false
        }

        return try {
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // 这条日志是"文案指不出真因"场景下的第一现场，务必留在 logcat
            Log.w(
                TAG,
                "ShizukuProvider 组件曾被禁用(state=$state)，已自动恢复为 ENABLED。" +
                    "本次进程拿不到 binder（server 那一轮投递已失败），需重新启动应用才生效"
            )
            onLog("⚠ ShizukuProvider 组件曾被禁用，已自动恢复；需重新启动应用才生效")
            true
        } catch (t: Throwable) {
            // DISABLED_USER 等由用户/系统设置的状态，普通 App 可能无权翻回来（静默失败或抛 SecurityException）
            Log.e(TAG, "恢复 ShizukuProvider 组件失败(state=$state)：${t.javaClass.name}: ${t.message}", t)
            onLog("✗ 恢复 ShizukuProvider 组件失败：${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /**
     * 预约立刻重启一次本应用进程（**可选**，仅适合后台常驻形态）。
     *
     * 走 `AlarmManager` 而不是直接 `startActivity`：Application 阶段没有 Activity 可依附，
     * 用闹钟预约 400ms 后拉起 launcher，再结束当前进程，可避免"进程被 kill 但没人重新拉起"。
     *
     * ⚠ 只应在 [ensureEnabled] 返回 true 后调用一次；`ensureEnabled` 改成幂等（修好就不再返回 true）
     * 天然保证不会死循环。
     */
    @JvmStatic
    fun restartProcessSoon(context: Context, delayMs: Long = 400) {
        val app = context.applicationContext
        try {
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch != null) {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = android.app.PendingIntent.getActivity(
                    app,
                    0,
                    launch,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_CANCEL_CURRENT
                )
                val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + delayMs, pi)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "预约重启失败：${t.javaClass.name}: ${t.message}", t)
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)
        }, 150)
    }

    /**
     * 用类引用而不是字符串常量：`rikka.shizuku.ShizukuProvider` 在清单里被按名声明，
     * R8 不会改名，`ComponentName(context, Cls)` 等价于 `ComponentName(packageName, "rikka.shizuku.ShizukuProvider")`，
     * 但写错类名的低级错误直接被编译器挡掉。
     */
    private fun component(context: Context): ComponentName =
        ComponentName(context, ShizukuProvider::class.java)
}
