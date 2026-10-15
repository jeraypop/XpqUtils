package com.google.android.accessibility.ext.acc

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.safecheck.AccIsOpenUtil
import com.google.android.accessibility.uiautomation.engine.InvisibleAutomation
import com.google.android.accessibility.uiautomation.shizuku.ShellResult
import java.text.Collator
import java.util.Locale

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
 * 名单的解析统一走 [parseEnabledServices]（本类内部与 `AccIsOpenUtil` 都调它）—— 各处别再自己写
 * `split(":")`，否则对「带空格的名单 / 空项」的处理会不一致（症状：重复追加、关不掉、回读误判失败）。
 *
 * 本类是「开启/关闭无障碍服务」的**唯一实现**：`XpqAcc` 的门面与 `XPQAccUtils.enableAccessibilityServices` /
 * `disableAccessibilityServices` 都只做委托，自己不再直接写这两个 Key。
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

    /**
     * 「已启用无障碍的应用」——**给人看**的形态：按应用聚合，带可读应用名。
     *
     * 与 [listEnabledAccessibilityServices] 的区别是维度不同：那里是**组件**（返回
     * `包名/包名.类名` 串，给代码比较用），这里是**应用**（一个应用注册多个无障碍服务也只出现一项）。
     * 字段名不翻译成大白话（术语是给开发者的），要展示给用户的是 [appLabel]。
     */
    data class EnabledAccessibilityApp(
        /** 应用包名，如 `com.example.app`。 */
        val packageName: String,
        /**
         * 应用名（`PackageManager` 取的 `android:label`）。
         * 取不到时**回退成包名** —— 见 [enabledAccessibilityApps] 里的包可见性说明。
         */
        val appLabel: String,
        /** 该应用已启用的无障碍服务组件，长式 `包名/包名.类名`，可能不止一个。 */
        val services: List<String>,
        /**
         * 是否已被系统解析生效（在 `AccessibilityManager` 的启用列表里）。
         * `false` = 只在名单里、实际没跑（设置界面显示「已开启」但不工作，正是宿主该提示用户的那种情况）。
         */
        val isActive: Boolean
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
     * 开启**一个**无障碍服务（写名单 + 总开关置 1 + 回读校验）。
     *
     * 与 [enableAccessibilityServices] 同一实现，这里只是单元素便捷重载。
     *
     * @param flatComponent 服务 ComponentName 展平串，长短式**都接受**（内部按 [canonicalComponent]
     *                      归一化后再比较与写入），如 `com.host/.MyAccService` 或全限定
     *                      `com.host/com.host.MyAccService`。
     * @param exclusive false（默认）= 纯增量：读当前名单追加，不影响设备上已开启的其它服务；
     *                  true = 独占：整体替换名单只保留传入的服务，其它已开启服务会被一并关闭。
     */
    @JvmStatic
    fun enableAccessibilityService(
        context: Context,
        flatComponent: String,
        exclusive: Boolean = false
    ): EnableResult = enableAccessibilityServices(context, listOf(flatComponent), exclusive)

    /**
     * **批量开启**无障碍服务 —— 名单只写**一次**（内部不逐个调用单元素版本）。
     *
     * @param flatComponents 组件串，长短式都接受；归一化后**去重**，顺序 = 写入名单的顺序。
     * @param exclusive false（默认）= 增量：保留设备上已开启的其它服务，只把这几个加进去；
     *                  true = 独占：名单整体替换成这几个（其它服务一并关闭）。
     *
     * 通道选择与 [enableAccessibilityService] 相同（Shizuku shell 优先 → WRITE_SECURE_SETTINGS）；
     * 回读校验要求**所有**目标都进了名单且总开关为 1，任一不满足即算失败。
     */
    @JvmStatic
    @JvmOverloads
    fun enableAccessibilityServices(
        context: Context,
        flatComponents: Collection<String>,
        exclusive: Boolean = false
    ): EnableResult {
        val targets = flatComponents.map { canonicalComponent(it) }.filter { it.isNotEmpty() }.distinct()
        if (targets.isEmpty()) return EnableResult(false, false, "组件名为空")
        if (tryEnableViaShell(targets, exclusive)) {
            val mode = if (exclusive) "独占开启" else "开启"
            return EnableResult(true, true, "已通过 Shizuku shell $mode，回读校验通过")
        }
        return enableViaWriteSecureSettings(context, targets, exclusive)
    }

    /**
     * 关闭**一个**无障碍服务（从名单移除；名单清空时总开关一并置 0，
     * 与系统行为一致：名单为空 → 系统把总开关置 0）。
     *
     * [flatComponent] 长短式都接受（比较走 [canonicalComponent]），
     * 所以不必关心名单里当初是以哪种形态写进去的。
     */
    @JvmStatic
    fun disableAccessibilityService(context: Context, flatComponent: String): EnableResult =
        disableAccessibilityServices(context, listOf(flatComponent))

    /**
     * **批量关闭**无障碍服务 —— 名单只写**一次**。
     *
     * 不在名单里的组件**不算失败**：「关掉」的目标本来就已达成。
     * 移除后名单为空 → 总开关一并置 0（与系统行为一致）。
     */
    @JvmStatic
    fun disableAccessibilityServices(
        context: Context,
        flatComponents: Collection<String>
    ): EnableResult {
        val targets = flatComponents.map { canonicalComponent(it) }.filter { it.isNotEmpty() }.distinct()
        if (targets.isEmpty()) return EnableResult(false, false, "组件名为空")
        if (tryDisableViaShell(targets)) {
            return EnableResult(true, true, "已通过 Shizuku shell 关闭，回读校验通过")
        }
        return disableViaWriteSecureSettings(context, targets)
    }

    /**
     * **独占开启**：名单整体替换成只保留 [flatComponent]（= 开启它 + 关闭设备上其它所有无障碍服务），
     * 再把总开关置 1。
     *
     * 与 [enableAccessibilityService]`(exclusive = true)` 写的是同一份东西，差别只在返回值：
     * 这里在写成功后**额外回读一次名单**，把「其它服务是否确实都已移除」写进 message，
     * 便于宿主与日志区分「开成功」和「开成功但还有服务赖着没走」（部分 ROM 会回填名单，
     * 而这种回填**不应该**被判成失败 —— 目标服务确实已经生效了）。
     */
    @JvmStatic
    fun enableExclusiveAccessibilityService(context: Context, flatComponent: String): EnableResult {
        if (flatComponent.isBlank()) return EnableResult(false, false, "组件名为空")
        val flat = canonicalComponent(flatComponent)
        val base = enableAccessibilityService(context, flat, exclusive = true)
        if (!base.success) return base
        // 比较一律归一化：名单里的形态可能与传入的不同（短式/长式），不比归一化会把目标自己也算成残留
        val rest = listEnabledAccessibilityServices(context).withoutComponents(listOf(flat))
        val tail = if (rest.isEmpty()) "；其它无障碍服务已全部关闭"
        else "；⚠ 名单里仍有其它服务未能移除：${rest.joinToString(", ")}"
        return base.copy(message = base.message + tail)
    }

    /**
     * 当前系统名单里的无障碍服务，按名单原顺序返回，**已归一化成统一的长式**
     * `包名/包名.类名`（见 [canonicalComponent]）—— 因此可以与
     * `AccIsOpenUtil.getEnabledServiceIds`（AMS 侧，同样归一化）直接互相比较。
     *
     * 优先走 shell（读到的就是真值，且不受 `@Readable(maxTargetSdk)` 影响）；shell 不可用时退回
     * ContentResolver —— `ENABLED_ACCESSIBILITY_SERVICES` 属于**不受限**的 secure 键，读它不需要额外权限。
     * 注意返回的是「名单」，不代表服务此刻正在运行（名单 + 总开关两者缺一不可）。
     */
    @JvmStatic
    @JvmOverloads
    fun listEnabledAccessibilityServices(context: Context = appContext): List<String> {
        val raw = readListViaShell() ?: runCatching {
            Settings.Secure.getString(context.contentResolver, listKey)
        }.getOrNull()
        return parseEnabledServices(raw).map { canonicalComponent(it) }
    }

    /**
     * 设备上已启用无障碍的**应用**列表，按应用名排序 —— **面向展示**用（这里是给"看"的，
     * 不是给"比"的；要组件串请用 [listEnabledAccessibilityServices]）。
     *
     * 数据来源是两个集合的**并集**：
     *  - 名单（`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，见 [listEnabledAccessibilityServices]）；
     *  - 系统解析后真正生效的那份（`AccIsOpenUtil.getEnabledServiceIds`，AMS 侧）。
     * 取并集是为了别漏项：名单读不到（shell 与 ContentResolver 都失败）时至少还有 AMS 那份。
     * 单个来源的局限见各自 KDoc，这里不重复。
     *
     * [EnabledAccessibilityApp.isActive] 为 false 的条目**照样返回**，不静默丢弃 ——
     * 「名单里有、系统却没认」恰恰是最需要让用户看见的异常（应用已卸载 / 服务被组件管理器禁用）。
     *
     * @param onlyActive true = 只返回真正生效的。此时**根本不读名单**，上来就取 AMS 那份，
     *                   因此也不走 shell，比默认分支快（适合只在界面上列"正在运行的"）。
     *                   false（默认）= 含「开了但没生效」的条目，由 `isActive` 区分。
     */
    @JvmStatic
    @JvmOverloads
    fun enabledAccessibilityApps(
        context: Context = appContext,
        onlyActive: Boolean = false
    ): List<EnabledAccessibilityApp> {
        val active = runCatching { AccIsOpenUtil.getEnabledServiceIds(context) }
            .getOrDefault(emptyList())
            .toSet()
        val all = if (onlyActive) active.toList()
        else (listEnabledAccessibilityServices(context) + active).distinct()
        if (all.isEmpty()) return emptyList()

        val pm = context.packageManager
        val collator = Collator.getInstance(Locale.getDefault())
        return all.groupBy { componentPackage(it) }
            .map { (pkg, comps) ->
                EnabledAccessibilityApp(
                    packageName = pkg,
                    appLabel = appLabel(pm, pkg),
                    services = comps,
                    isActive = comps.any { it in active }
                )
            }
            .sortedWith(compareBy(collator) { it.appLabel })
    }

    /**
     * 名单原文 → 组件列表：冒号分隔、**逐项 trim**、丢弃空项。传入 null 视为空名单。
     *
     * 名单的**唯一解析入口**。系统自己写入时不会带空格，但名单可能被别的工具或 `settings put`
     * 写成 `a : b` 这种带空格的形态 —— 各处若各写一遍 `split(":")`，就会出现「有的地方认、
     * 有的地方不认」的不一致（典型症状：重复追加、关不掉、回读校验误判失败）。
     */
    @JvmStatic
    fun parseEnabledServices(raw: String?): List<String> =
        raw.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * **组件串归一化**：统一成**长式** `包名/包名.类名`（`ComponentName.flattenToString()`）。
     *
     * 无障碍组件串有两种合法形态，同一个服务会长得不一样：
     *  - 长式 `com.host/com.host.MyAccService` —— `Settings.Secure` 名单与设置界面写的形态；
     *  - 短式 `com.host/.MyAccService` —— `AccessibilityServiceInfo.getId()`（`flattenToShortString()`）
     *    与 `ComponentName.flattenToShortString()` 的展示优化形态（类名与包名同前缀时压掉）。
     *
     * **所有跨来源的字符串比较都必须先过这里**，否则同一服务会被判成两个（症状：重复追加、
     * 关不掉、独占开启后回读误报"仍有其它服务"）。
     *
     * 解析失败（不含 `/`、或 `/` 后为空 —— 例如遗留脏数据、非组件条目）时**原样返回**，
     * 保持"读到什么就是什么"，不丢项。
     */
    @JvmStatic
    fun canonicalComponent(flatComponent: String?): String {
        val raw = flatComponent?.trim().orEmpty()
        return ComponentName.unflattenFromString(raw)?.flattenToString() ?: raw
    }

    /** 名单里是否**全部**含有 [targets]（形态无关，按 [canonicalComponent] 比较）。 */
    private fun List<String>.containsAllComponent(targets: List<String>): Boolean {
        val have = map { canonicalComponent(it) }.toHashSet()
        return targets.all { it in have }
    }

    /** 名单里是否含有 [targets] 中的**任意一个**（用于判断"是否已彻底移除"）。 */
    private fun List<String>.containsAnyComponent(targets: List<String>): Boolean {
        val want = targets.toSet()
        return any { canonicalComponent(it) in want }
    }

    /** 移除所有与 [targets] 等价的组件，其余原样保留。 */
    private fun List<String>.withoutComponents(targets: List<String>): List<String> {
        val want = targets.toSet()
        return filter { canonicalComponent(it) !in want }
    }

    /** 组件串 → 包名；解析不出（不含 `/`、或 `/` 前为空这类脏数据）时退回 `/` 之前那段。 */
    private fun componentPackage(flatComponent: String): String =
        ComponentName.unflattenFromString(flatComponent)?.packageName
            ?: flatComponent.substringBefore('/')

    /**
     * 应用名；**取不到就回退包名**，保证列表项永远有可显示的文字。取不到只有两种情况：
     *  - 应用已卸载（名单残留的脏数据）；
     *  - Android 11+ 的**包可见性**：宿主未声明 `QUERY_ALL_PACKAGES`、也没在 `<queries>` 里覆盖
     *    无障碍服务（`<intent><action android:name="android.accessibilityservice.AccessibilityService"/></intent>`）
     *    时，读不到别的应用的信息 —— 此时列表里会显示成包名。宿主加上该 `<queries>` 即可拿到真实应用名。
     *    （本库不替宿主往清单里注入 `<queries>`，改清单属于宿主的事。）
     */
    private fun appLabel(pm: PackageManager, packageName: String): String = runCatching {
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

    // ---------- 路径 1：Shizuku shell（uid 2000 自带 WRITE_SECURE_SETTINGS） ----------

    private fun tryEnableViaShell(targets: List<String>, exclusive: Boolean): Boolean {
        val cur = readListViaShell() ?: return false
        // 增量模式且都已在名单、总开关已开 → 无需写（按归一化比较，避免同一服务以另一种形态重复追加）
        if (!exclusive && parseEnabledServices(cur).containsAllComponent(targets) && readSwitchViaShell() == "1") return true
        val merged = if (exclusive) targets.joinToString(SEPARATOR) else mergeComponents(cur, targets)
        putViaShell(listKey, merged) ?: return false
        putViaShell(switchKey, "1") ?: return false
        // 回读校验两个 Key（名单 + 总开关缺一不可）
        val backList = readListViaShell() ?: return false
        return parseEnabledServices(backList).containsAllComponent(targets) && readSwitchViaShell() == "1"
    }

    private fun tryDisableViaShell(targets: List<String>): Boolean {
        val cur = readListViaShell() ?: return false
        val rest = parseEnabledServices(cur).withoutComponents(targets)
        putViaShell(listKey, rest.joinToString(SEPARATOR)) ?: return false
        if (rest.isEmpty()) putViaShell(switchKey, "0")  // 名单清空 → 总开关一并关
        val backList = readListViaShell() ?: return false
        val gone = !parseEnabledServices(backList).containsAnyComponent(targets)
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
        targets: List<String>,
        exclusive: Boolean
    ): EnableResult {
        val notGranted = checkNotGranted(context)
        if (notGranted != null) return notGranted
        return try {
            val cr = context.contentResolver
            val cur = Settings.Secure.getString(cr, listKey) ?: ""
            val merged = if (exclusive) targets.joinToString(SEPARATOR) else mergeComponents(cur, targets)
            Settings.Secure.putString(cr, listKey, merged)
            Settings.Secure.putString(cr, switchKey, "1")
            val okList = parseEnabledServices(Settings.Secure.getString(cr, listKey)).containsAllComponent(targets)
            val okSwitch = Settings.Secure.getString(cr, switchKey) == "1"
            if (okList && okSwitch) EnableResult(true, false, "已通过 WRITE_SECURE_SETTINGS 开启，回读校验通过")
            else EnableResult(false, false, "写入后回读不一致（部分 ROM 限制 secure 表写入）")
        } catch (t: Throwable) {
            EnableResult(false, false, "WRITE_SECURE_SETTINGS 写入失败: ${t.message}")
        }
    }

    private fun disableViaWriteSecureSettings(context: Context, targets: List<String>): EnableResult {
        val notGranted = checkNotGranted(context)
        if (notGranted != null) return notGranted
        return try {
            val cr = context.contentResolver
            val rest = parseEnabledServices(Settings.Secure.getString(cr, listKey)).withoutComponents(targets)
            Settings.Secure.putString(cr, listKey, rest.joinToString(SEPARATOR))
            if (rest.isEmpty()) Settings.Secure.putString(cr, switchKey, "0")
            val gone = !parseEnabledServices(Settings.Secure.getString(cr, listKey)).containsAnyComponent(targets)
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

    /**
     * 把 [targets] 追加进名单（冒号分隔）；已存在（按 [canonicalComponent] 比较，任意形态都算存在）的不重复加。
     * 顺带把现有名单规范化（trim 后重新 join），避免 `a : b` 这种脏名单被继续带下去。
     */
    private fun mergeComponents(cur: String, targets: List<String>): String {
        val list = parseEnabledServices(cur)
        val have = list.map { canonicalComponent(it) }.toHashSet()
        val missing = targets.filterNot { it in have }
        return if (missing.isEmpty()) cur else (list + missing).joinToString(SEPARATOR)
    }
}
