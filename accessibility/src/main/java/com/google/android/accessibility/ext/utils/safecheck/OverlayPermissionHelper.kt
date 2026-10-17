package com.google.android.accessibility.ext.utils.safecheck

import android.content.Context
import android.provider.Settings
import com.google.android.accessibility.ext.acc.AccessibilityEnableHelper
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * 悬浮窗权限（`SYSTEM_ALERT_WINDOW`）的读取与开启。
 *
 * ⚠ **它和库内那套「无障碍名单」机制完全不同**：`SYSTEM_ALERT_WINDOW` 是 **appops 特殊权限**、
 * 不是运行时权限 —— `Settings.canDrawOverlays()` 的实质就是查 `OP_SYSTEM_ALERT_WINDOW` 的
 * appops 模式（`Settings.java:4029` → `isCallingPackageAllowedToDrawOverlays` →
 * `AppOpsManager.noteOpNoThrow`）。因此除 shell 之外的路全部走不通：
 * - `pm grant` 无效：它不是 dangerous 权限，进不了运行时权限通道；
 * - 写 `Settings.Secure` 无效：那个表里**没有**这个键 ⇒ `WRITE_SECURE_SETTINGS` 也帮不上；
 * - 无障碍服务 / UiAutomation 也没有 `MANAGE_APP_OPS_MODES`，不能代开。
 *
 * **唯一能「代码开启」的路 = shell（uid 2000）**：`appops set <pkg> SYSTEM_ALERT_WINDOW allow`。
 * shell 侧确实持有 `MANAGE_APP_OPS_MODES`（AOSP `packages/Shell/AndroidManifest.xml` 有声明），
 * 且 `AppOpsService.setMode` 对 `OP_SYSTEM_ALERT_WINDOW` **没有任何特殊拦截**，也不要求调用者与目标同 uid。
 *
 * ⚠ 国产 ROM（MIUI / HyperOS / EMUI / ColorOS）往往另有一层悬浮窗开关，appops 设成 allow 之后
 * 窗口仍可能被拦 —— 所以 [canDrawOverlays] 只是**必要条件**，最终判据应以「`addView` 是否成功」为准。
 */
object OverlayPermissionHelper {

    /**
     * 开启结果。[AccessibilityEnableHelper.EnableResult] 的悬浮窗版 —— 差别是这条链路**只有 shell 一条路**，
     * 不存在 WRITE_SECURE_SETTINGS 分支，所以 [viaShell] 的语义是「本次是否真的执行了 shell 命令」。
     */
    data class OverlayResult(
        val success: Boolean,
        /** true = 本次执行了 shell 命令；false = 已授权，直接短路、没有执行。 */
        val viaShell: Boolean,
        val message: String
    )

    /**
     * 当前是否已获得悬浮窗权限（等价 appops `SYSTEM_ALERT_WINDOW` 已允许）。
     *
     * 纯进程内查询、**零成本**，随便调；不要为了查状态去 fork `appops get`。
     */
    @JvmStatic
    @JvmOverloads
    fun canDrawOverlays(context: Context = appContext): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * 通过 shell 开启悬浮窗权限 —— **阻塞方法**（要 fork 一个 `app_process`，几百毫秒），
     * 必须在后台线程调用。
     *
     * 两条短路/校验规则：
     * - **已授权直接返回**，不再 fork（见 [canDrawOverlays]）；
     * - 写完用 [canDrawOverlays] 在**进程内**回读校验，**刻意不再 fork 一次 `appops get`**。
     *
     * shell 通道不可用（Shizuku 未连接）时返回 `success = false`：调用方应改走
     * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 引导用户手动授权 —— 这条权限没有第二条自动通道。
     *
     * ⚠ 返回值 `success = true` 只代表 appops 已放行，不代表窗口一定能加上（厂商二次拦截见类注释）。
     */
    @JvmStatic
    @JvmOverloads
    fun enableOverlayPermission(context: Context = appContext): OverlayResult {
        if (Settings.canDrawOverlays(context)) {
            return OverlayResult(true, false, "已拥有悬浮窗权限，跳过")
        }
        val pkg = context.packageName
        val r = AccessibilityEnableHelper.execShell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            ?: return OverlayResult(false, true, "shell 通道不可用（Shizuku 未连接），只能引导用户手动授权")
        if (r.exitCode != 0) {
            val err = r.stderr.trim().take(200)
            return OverlayResult(false, true, "appops 执行失败(exit=${r.exitCode})${if (err.isEmpty()) "" else ": $err"}")
        }
        return if (Settings.canDrawOverlays(context)) {
            OverlayResult(true, true, "已通过 appops 开启")
        } else {
            // shell 报成功但 appops 仍不是 allow：多半被厂商的权限管理改写了，需要用户介入
            OverlayResult(false, true, "appops 返回成功但回读仍未授权（疑似被厂商权限管理拦截）")
        }
    }
}
