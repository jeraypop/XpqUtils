package com.google.android.accessibility.ext.acc

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.google.android.accessibility.uiautomation.engine.InvisibleAutomation
import com.google.android.accessibility.uiautomation.shizuku.ShellResult

/**
 * 无障碍服务开启器：让宿主"直接开启某个无障碍服务"，无需用户去系统设置手动点亮。
 *
 * 两条路径，按可用性自动选择：
 *  1. **Shizuku shell 路径（推荐）**：UiAutomation 通道的 shell UserService（uid 2000）
 *     自带 WRITE_SECURE_SETTINGS，UserService 绑定后即可 `settings put secure` 直接写入，
 *     宿主零授权。甚至不要求 UiAutomation 注册成功——bind UserService（connect [2/3] 步）完成即可。
 *  2. **WRITE_SECURE_SETTINGS 路径**：宿主被授权一次
 *     `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` 后，
 *     直接 [Settings.Secure.putString] 写入，不依赖 Shizuku。
 *
 * 关键约束（两个 Key 必须成对写，缺一不可）：
 *  - [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] 只是"名单"；
 *  - [Settings.Secure.ACCESSIBILITY_ENABLED] 是无障碍总开关，真正决定服务会不会被系统绑定运行。
 *  - 只写名单不写总开关 → 设置里显示已开启、实际不运行（典型坑）。
 *  - 名单是冒号分隔的多服务列表，必须**读-改-写追加**，直接覆盖会踢掉别人的服务。
 *
 * 所有方法都是阻塞操作（binder + settings 命令 + waitFor），**勿在主线程调用**。
 */
object AccessibilityEnableHelper {

    /** 开启/关闭结果。 */
    data class EnableResult(
        val success: Boolean,
        /** true = 走的 Shizuku shell 路径；false = WRITE_SECURE_SETTINGS 路径。 */
        val viaShell: Boolean,
        val message: String
    )

    /** 名单分隔符（系统约定）。 */
    private const val SEPARATOR = ":"
    private val listKey = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private val switchKey = Settings.Secure.ACCESSIBILITY_ENABLED

    /**
     * shell 通道是否可用。UserService 绑定后即可用（无需 UiAutomation 注册成功）。
     * 探测方式：执行一条空命令看 exitCode。
     */
    @JvmStatic
    fun isShellAvailable(): Boolean = try {
        InvisibleAutomation.exec("true")?.exitCode == 0
    } catch (_: Throwable) {
        false
    }

    /**
     * 通用 shell 执行入口（uid 2000，等价 adb shell；命令**不带** "adb shell" 前缀）。
     * 未绑定 UserService 时返回 null。阻塞，勿在主线程调用。
     */
    @JvmStatic
    fun execShell(command: String): ShellResult? = InvisibleAutomation.exec(command)

    /**
     * 开启指定无障碍服务（写名单 + 总开关置 1 + 回读校验）。
     *
     * @param flatComponent 服务 ComponentName 展平串，
     *                      如 "com.host/.MyAccService" 或全限定 "com.host/com.host.MyAccService"
     * @param exclusive false（默认）= 纯增量：读当前名单追加，不影响设备上已开启的其它服务；
     *                  true = 独占：整体替换名单只保留传入的服务，其它已开启服务会被一并关闭。
     */
    @JvmStatic
    fun enableAccessibilityService(
        context: Context,
        flatComponent: String,
        exclusive: Boolean = false
    ): EnableResult {
        if (flatComponent.isBlank()) return EnableResult(false, false, "组件名为空")
        if (tryEnableViaShell(flatComponent, exclusive)) {
            val mode = if (exclusive) "独占开启" else "开启"
            return EnableResult(true, true, "已通过 Shizuku shell $mode，回读校验通过")
        }
        return enableViaWriteSecureSettings(context, flatComponent, exclusive)
    }

    /**
     * 关闭指定无障碍服务（从名单移除；名单清空时总开关一并置 0，
     * 与系统行为一致：名单为空 → 系统把总开关置 0）。
     */
    @JvmStatic
    fun disableAccessibilityService(context: Context, flatComponent: String): EnableResult {
        if (flatComponent.isBlank()) return EnableResult(false, false, "组件名为空")
        if (tryDisableViaShell(flatComponent)) {
            return EnableResult(true, true, "已通过 Shizuku shell 关闭，回读校验通过")
        }
        return disableViaWriteSecureSettings(context, flatComponent)
    }

    // ---------- 路径 1：Shizuku shell（uid 2000 自带 WRITE_SECURE_SETTINGS） ----------

    private fun tryEnableViaShell(flat: String, exclusive: Boolean): Boolean {
        val cur = readListViaShell() ?: return false
        // 增量模式且已在名单、总开关已开 → 无需写
        if (!exclusive && cur.split(SEPARATOR).contains(flat) && readSwitchViaShell() == "1") return true
        val merged = if (exclusive) flat else mergeComponent(cur, flat)
        putViaShell(listKey, merged) ?: return false
        putViaShell(switchKey, "1") ?: return false
        // 回读校验两个 Key（名单 + 总开关缺一不可）
        val backList = readListViaShell() ?: return false
        return backList.split(SEPARATOR).contains(flat) && readSwitchViaShell() == "1"
    }

    private fun tryDisableViaShell(flat: String): Boolean {
        val cur = readListViaShell() ?: return false
        val rest = cur.split(SEPARATOR).filter { it.isNotEmpty() && it != flat }
        putViaShell(listKey, rest.joinToString(SEPARATOR)) ?: return false
        if (rest.isEmpty()) putViaShell(switchKey, "0")  // 名单清空 → 总开关一并关
        val backList = readListViaShell() ?: return false
        val gone = !backList.split(SEPARATOR).contains(flat)
        val switchOff = rest.isNotEmpty() || readSwitchViaShell() != "1"
        return gone && switchOff
    }

    /** 读名单；shell 不可用返回 null（以此区分"空名单"与"没通道"）。 */
    private fun readListViaShell(): String? = readViaShell(listKey)

    private fun readSwitchViaShell(): String? = readViaShell(switchKey)

    private fun readViaShell(key: String): String? = try {
        InvisibleAutomation.exec("settings get secure $key")?.stdout?.trim()
            ?.takeUnless { it.isEmpty() || it == "null" } ?: ""
    } catch (_: Throwable) {
        null
    }

    private fun putViaShell(key: String, value: String): ShellResult? = try {
        // 单引号包裹防注入；组件串本身不含单引号，防御性剥离
        InvisibleAutomation.exec("settings put secure $key '${value.replace("'", "")}'")
            ?.takeIf { it.exitCode == 0 }
    } catch (_: Throwable) {
        null
    }

    // ---------- 路径 2：WRITE_SECURE_SETTINGS（宿主被 adb 授权后） ----------

    private fun enableViaWriteSecureSettings(
        context: Context,
        flat: String,
        exclusive: Boolean
    ): EnableResult {
        val notGranted = checkNotGranted(context)
        if (notGranted != null) return notGranted
        return try {
            val cr = context.contentResolver
            val cur = Settings.Secure.getString(cr, listKey) ?: ""
            val merged = if (exclusive) flat else mergeComponent(cur, flat)
            Settings.Secure.putString(cr, listKey, merged)
            Settings.Secure.putString(cr, switchKey, "1")
            val okList = (Settings.Secure.getString(cr, listKey) ?: "")
                .split(SEPARATOR).contains(flat)
            val okSwitch = Settings.Secure.getString(cr, switchKey) == "1"
            if (okList && okSwitch) EnableResult(true, false, "已通过 WRITE_SECURE_SETTINGS 开启，回读校验通过")
            else EnableResult(false, false, "写入后回读不一致（部分 ROM 限制 secure 表写入）")
        } catch (t: Throwable) {
            EnableResult(false, false, "WRITE_SECURE_SETTINGS 写入失败: ${t.message}")
        }
    }

    private fun disableViaWriteSecureSettings(context: Context, flat: String): EnableResult {
        val notGranted = checkNotGranted(context)
        if (notGranted != null) return notGranted
        return try {
            val cr = context.contentResolver
            val cur = Settings.Secure.getString(cr, listKey) ?: ""
            val rest = cur.split(SEPARATOR).filter { it.isNotEmpty() && it != flat }
            Settings.Secure.putString(cr, listKey, rest.joinToString(SEPARATOR))
            if (rest.isEmpty()) Settings.Secure.putString(cr, switchKey, "0")
            val gone = !(Settings.Secure.getString(cr, listKey) ?: "")
                .split(SEPARATOR).contains(flat)
            val switchOff = rest.isNotEmpty() || Settings.Secure.getString(cr, switchKey) != "1"
            if (gone && switchOff) EnableResult(true, false, "已通过 WRITE_SECURE_SETTINGS 关闭，回读校验通过")
            else EnableResult(false, false, "写入后回读不一致（部分 ROM 限制 secure 表写入）")
        } catch (t: Throwable) {
            EnableResult(false, false, "WRITE_SECURE_SETTINGS 写入失败: ${t.message}")
        }
    }

    private fun checkNotGranted(context: Context): EnableResult? {
        val granted = context.checkSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) null else EnableResult(
            false, false,
            "shell 通道不可用，且宿主无 WRITE_SECURE_SETTINGS 权限。" +
                    "可让用户执行一次: adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS"
        )
    }

    /** 追加组件到名单（冒号分隔）；已存在原样返回；空名单直接放组件。 */
    private fun mergeComponent(cur: String, flat: String): String = when {
        cur.split(SEPARATOR).contains(flat) -> cur
        cur.isEmpty() -> flat
        else -> "$cur$SEPARATOR$flat"
    }
}
