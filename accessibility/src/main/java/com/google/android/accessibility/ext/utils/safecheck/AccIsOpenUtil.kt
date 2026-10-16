package com.google.android.accessibility.ext.utils.safecheck

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.google.android.accessibility.ext.acc.AccessibilityEnableHelper
import com.google.android.accessibility.ext.acc.EngineMode
import com.google.android.accessibility.ext.acc.XpqAcc
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.window.LogWrapper_NoNet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Company    : 
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/9/4  4:54
 * Description:This is AccIsOpenUtil
 */
object AccIsOpenUtil {
    private const val TAG = "AccIsOpenUtil"

    private val DEFAULT_WHITE_LIST = listOf(
        "com.google.android.accessibility.selecttospeak.SelectToSpeakService",
        "com.dianming.phoneapp.MyAccessibilityService",
    )

    /**
     * 运行时追加的白名单特征，由 [addWhiteListFeature] / [removeWhiteListFeature] 维护。
     *
     * 用 [CopyOnWriteArrayList] 而非 `mutableListOf`：[whiteAccessibilityIsEnabled] 的读**可能落在后台线程**
     * （它自己被别的判断包着），而写入是宿主在主线程调 —— 普通 ArrayList 会抛 `ConcurrentModificationException`。
     * 这里写少读多，正是 CopyOnWrite 的适用场景。
     */
    private val remoteWhiteList = CopyOnWriteArrayList<String>()
    private val allWhiteList get() = DEFAULT_WHITE_LIST + remoteWhiteList

    /**
     * [checkSelectToSpeakAsync] 的专用后台线程池（不借 `AppExecutors` 的共享池，理由见下）：
     * - **单线程** → 重复调用天然**串行**，不会两次并发去写无障碍名单（独占开启是整表替换，并发写会互相覆盖）；
     * - **守护线程** → 不阻进程退出（与 `AppExecutors` 同款）；
     * - **独立池** → 本任务里有最长 `time` 毫秒的 `SystemClock.sleep`，放进共享池会把同池的其它任务堵住。
     */
    private val checkWorker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xpq-check-sts").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * **白名单**里的无障碍服务是否已启用。
     *
     * ⚠ 语义**不是**「系统里有没有无障碍服务在跑」，而是「总开关开着，且我认得的那几个服务
     * （[allWhiteList]）在名单/生效列表里」。三条判定 = 一个前提 + 两个来源取**或**：
     *  1. `ACCESSIBILITY_ENABLED == 1`，否则直接 false（总开关是前提，名单还在也没用）；
     *  2. AMS 解析后真正生效的列表（[getEnabledServiceIds]）命中白名单 → true；
     *  3. 否则再看 secure 名单（[getEnabledServiceIdsFromSecure]）→ 命中 → true。
     * 取「或」是为了兜住 AMS 侧的静默失真（`getServiceLocked()` 拿不到就 `return emptyList()`），
     * 顺带也兜住了 Android 14 以下 AMS 漏 `feedbackType = 0` 服务的问题。
     *
     * 三处反直觉，用之前先看清楚：
     *  - 用户只开了 TalkBack 等**非白名单**服务 → **false**；
     *  - **纯 UiAutomation（Shizuku）模式通常也是 false**（系统侧没有真实无障碍服务实例），
     *    所以**不能**拿它判断「库能不能干活」——那要看 `XpqAcc.currentService()` 或 driver 的 `isConnected`；
     *  - 匹配是 `id.contains(白名单特征串)` 的**子串包含**：白名单里写类名即可，不必写完整组件串，
     *    但宿主**换了包名 / 换了服务类名之后这里会静默恒 false**（改名单要同步改 [DEFAULT_WHITE_LIST]）。
     */
    @JvmOverloads
    @JvmStatic
    fun whiteAccessibilityIsEnabled(context: Context = appContext): Boolean {
        val global = Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Log.d(TAG, "无障碍总开关 accessibility_enabled=$global")
        if (global != 1){
            sendLog("系统无障碍未初始化，请先在系统设置中手动开启一次")
            return false
        }

        val ids = getEnabledServiceIds(context)
        if (isAnyInWhiteList(ids, allWhiteList)) {
            Log.d(TAG, "命中来源于 AccessibilityManager")
            return true
        }
        val secureIds = getEnabledServiceIdsFromSecure(context)
        return isAnyInWhiteList(secureIds, allWhiteList).also {
            if (it) Log.d(TAG, "命中来源于 Settings.Secure")
        }
    }

    /**
     * **[严格版] 设备上是否「**只**开着白名单里的那一个无障碍服务」**。
     *
     * 与 [whiteAccessibilityIsEnabled] 的差别就一句话：那个是「**存在**白名单服务在启用中」
     * （同时还开着别的无障碍服务也算 true）；这个是「**有且仅有**白名单服务在启用中」，
     * 用来确认当前没有别的无障碍服务在干扰（宿主自己的服务、第三方服务等）。
     *
     * 三条**同时**满足才是 true：
     *  1. `ACCESSIBILITY_ENABLED == 1`（总开关，与 [whiteAccessibilityIsEnabled] 同）；
     *  2. `getEnabledServiceIds()` 的**大小恰好为 1**；
     *  3. 这唯一的一条命中白名单（`id.contains(特征串)`，见 [allWhiteList]）。
     *
     * ⚠ 口径选择：判「大小」用的是 **AMS 生效列表**，不是 secure 名单 —— 名单是「被写进去的清单」，
     * 可能含**已卸载**等脏数据，会把"只有一个"直接判失真；AMS 列表 ≈ 名单 ∩ 已安装，更贴近
     * 「设置界面里正开着几个服务」（也正是这个方法名里"系统启用列表"的含义）。
     * 代价：AMS 侧可能**静默失真**（`getServiceLocked()` 拿不到服务时直接返回空表，见
     * [getEnabledServiceIds]）→ 此时 size = 0，本函数返回 false —— 严格版本取安全侧。
     *
     * 注：本函数**不**看 secure 名单，也不会像 [whiteAccessibilityIsEnabled] 那样拿名单兜底；
     * 要"宽松版"请用 [whiteAccessibilityIsEnabled]。
     */
    @JvmOverloads
    @JvmStatic
    fun onlyWhiteAccessibilityIsEnabled(context: Context = appContext): Boolean {
        val global = Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Log.d(TAG, "无障碍总开关 accessibility_enabled=$global")
        if (global != 1) {
            sendLog("系统无障碍未初始化，请先在系统设置中手动开启一次")
            return false
        }

        val ids = getEnabledServiceIds(context)
        Log.d(TAG, "当前启用服务（AMS）=$ids，size=${ids.size}，白名单=$allWhiteList")
        if (ids.size != 1) return false
        return isAnyInWhiteList(ids, allWhiteList)
    }

    /**
     * **追加**白名单特征 —— 宿主自定义扩展的口子，替代改库源码里的 [DEFAULT_WHITE_LIST]。
     *
     * 典型用途：宿主换过包名 / 换过服务类名之后，[whiteAccessibilityIsEnabled] 会因为特征串对不上而
     * **静默恒 false**；宿主在 `Application.onCreate` 里把自己当前的服务类名补进来即可自愈。
     *
     * 特征串按 `id.contains(特征串)` **子串匹配**（见 [whiteAccessibilityIsEnabled]），
     * 所以传**服务类名**（如 `com.foo.bar.MyAccService`）就够，不必写完整组件串。
     * 空串与已存在的重复项会被忽略。
     *
     * ⚠ **仅进程内生效、不落盘** —— 进程重建后要重新调用（放 `Application.onCreate` 最省事）。
     */
    @JvmStatic
    fun addWhiteListFeature(vararg features: String) {
        features.forEach { feature ->
            val f = feature.trim()
            if (f.isNotEmpty() && f !in remoteWhiteList) remoteWhiteList.add(f)
        }
    }

    /**
     * **移除**此前由 [addWhiteListFeature] 追加的特征 —— 只动运行时追加项，
     * [DEFAULT_WHITE_LIST] 里的默认项移不掉（想屏蔽默认项请改库源码）。
     * 传入不存在的特征不报错，静默忽略。
     */
    @JvmStatic
    fun removeWhiteListFeature(vararg features: String) {
        val targets = features.map { it.trim() }.filter { it.isNotEmpty() }
        if (targets.isEmpty()) return
        remoteWhiteList.removeAll(targets)
    }

    /** 当前生效的**全量**白名单特征（[DEFAULT_WHITE_LIST] + 运行时追加项）快照，排查用。 */
    @JvmStatic
    fun whiteListFeatures(): List<String> = allWhiteList.toList()

    /**
     * 检查「随选朗读独占开启」是否就绪，不就绪则**自动尝试开启** —— **这是阻塞方法**：
     * 内部会走 `XpqAcc.enableAccessibilityServiceOnly`（shell 路径要 fork sh，见 `AccessibilityEnableHelper`）
     * 并 `SystemClock.sleep(time)` 等系统生效，最长阻塞约 `time` 毫秒。
     * **不要在 UI 线程直接调**；不想自己起线程就用 [checkSelectToSpeakAsync]。
     *
     * 分流：
     * - **无障碍模式** → 只记日志提示「建议切换到 Shizuku」（wx 里用无障碍极大概率被检测到），返回 false；
     * - **Shizuku 模式** → 已满足（[onlyWhiteAccessibilityIsEnabled]：**只**开着白名单那一个）直接 true；
     *   否则独占开启随选朗读：**开启动作本身失败**（无 shell 也无 `WRITE_SECURE_SETTINGS`）时
     *   直接返回 false，**不再白等 [time]**；动作成功才等 [time] 再**回读校验**，满足 true，
     *   否则 toast 提示并 false；
     * - **其它情况**（未连接等）→ false。
     *
     * ⚠ 判定用的是严格版 [onlyWhiteAccessibilityIsEnabled]（有且仅有白名单服务），不是宽松版
     * [whiteAccessibilityIsEnabled] —— 后者「同时还开着别的无障碍」也算 true，与本方法「降低被检测概率」的目的不符。
     *
     * @param time 开启后等待系统生效的时长（毫秒），默认 1500。
     */
    @JvmStatic
    @JvmOverloads
    fun checkSelectToSpeak(time: Long = 1500L): Boolean{
        if (XpqAcc.mode == EngineMode.ACCESSIBILITY_SERVICE){
            LogWrapper_NoNet.logAppend_NoNet("当前工作模式：无障碍服务(在wx中使用无障碍极大概率会被检测到) 建议切换到 Shizuku")
            sendLog("当前工作模式：无障碍服务(在wx中使用无障碍极大概率会被检测到) 建议切换到 Shizuku")
            return false
        }else if (XpqAcc.mode == EngineMode.UIAUTOMATION){
            LogWrapper_NoNet.logAppend_NoNet("当前工作模式：Shizuku")
            sendLog("当前工作模式：Shizuku")
            val open = "随选朗读已开启「作用：保证shizuku模式下在wx里正常工作」 且 其它无障碍都关闭「作用：降低被检测到概率」"
            val close = "请开启随选朗读「作用：保证shizuku模式下在wx里正常工作」 并 关闭其它所有的无障碍「作用：降低被检测到概率」"
            // 失败收口：toast（内部已切主线程）+ 双通道日志。新增失败路径时改这一处即可。
            fun reportFail(): Boolean {
                AliveUtils.toast(msg = close)
                LogWrapper_NoNet.logAppend_NoNet(close)
                sendLog(close)
                return false
            }
            if (!AccIsOpenUtil.onlyWhiteAccessibilityIsEnabled()){
                //请开启随选朗读并关闭其它无障碍
                val result = XpqAcc.enableAccessibilityServiceOnly(XpqAcc.SELECTTOSPEAK)
                // 开启动作本身就没成功（既无 shell 也无 WRITE_SECURE_SETTINGS）→ 注定不会生效，
                // 不必再白等 time 毫秒才回读；直接按失败处理（异步版里也省掉一段无谓延迟）。
                if (!result.success) return reportFail()
                SystemClock.sleep(time)
                if (AccIsOpenUtil.onlyWhiteAccessibilityIsEnabled()){
                    LogWrapper_NoNet.logAppend_NoNet(open)
                    sendLog(open)
                    return true
                }else{
                    return reportFail()
                }
            }
            else{
                LogWrapper_NoNet.logAppend_NoNet(open)
                sendLog(open)
                return true
            }
        }
        return false
    }

    /**
     * [checkSelectToSpeak] 的**异步版** —— 检查 + 开启 + 等待全部在库内子线程执行，
     * 宿主**不必自己包线程**，在主线程直接调用即可（与 [XpqAcc.connectUiAutomation] 同款：
     * 内部切后台，回调切回主线程）。
     *
     * 结果有两条通道，按需要选：
     * - **界面提示不用管**：[checkSelectToSpeak] 失败时自己会 toast（`AliveUtils.toast` 内部已切主线程），
     *   日志同理（`sendLog` / `LogWrapper_NoNet` 本来就该在子线程写文件）；
     * - **要拿结果做后续动作**：用 [onResult]，**保证在主线程**回调（与库内其它回调一致）。
     *
     * ⚠ 重复调用会在同一个**单线程池**里排队串行执行（每次都要完整等 `time` 毫秒），
     *   好处是不会并发改无障碍名单；要防连点请自行节流。
     *
     * ```kotlin
     * // 宿主：主线程直接调，不用 Thread { }
     * AccIsOpenUtil.checkSelectToSpeakAsync { ok -> if (ok) startWork() else showGuide() }
     * ```
     *
     * @param time     同 [checkSelectToSpeak]：开启后等待系统生效的时长（毫秒）。
     * @param onResult 可选回调，参数 = [checkSelectToSpeak] 的返回值，**在主线程**回调；
     *                 不传则纯靠库内 toast / 日志反馈（等价于调同步版，但不阻塞调用线程）。
     */
    @JvmStatic
    @JvmOverloads
    fun checkSelectToSpeakAsync(time: Long = 1500L, onResult: ((Boolean) -> Unit)? = null) {
        checkWorker.execute {
            val ok = runCatching { checkSelectToSpeak(time) }.getOrDefault(false)
            if (onResult != null) {
                Handler(Looper.getMainLooper()).post { runCatching { onResult(ok) } }
            }
        }
    }

    /**
     * 系统**解析后**认为已启用的无障碍服务（binder 到 system_server 的 AccessibilityManager）。
     *
     * 与 [getEnabledServiceIdsFromSecure] 的本质差别：这里是「名单 ∩ 已安装 ∩ feedbackType 命中」
     * 三者交集 —— 名单里的脏数据（组件已卸载 / 被组件管理工具禁用 / 写错了）**不会**出现在这里。
     * 代价是它可能**静默失真**：`AccessibilityManager.getEnabledAccessibilityServiceList` 在
     * `getServiceLocked()` 拿不到服务时直接 `return Collections.emptyList()`（`AccessibilityManager.java:878`），
     * 与「确实一个服务都没启用」无法区分 —— 这正是 [whiteAccessibilityIsEnabled] 还需要第二个来源的原因。
     *
     * ⚠ 返回值形态与 [getEnabledServiceIdsFromSecure] **已统一**为长式 `包名/包名.类名`
     * （`ComponentName.flattenToString()`），见 [toCanonicalComponent]。
     * 系统原始给的是 `AccessibilityServiceInfo.getId()`，即
     * `mComponentName.flattenToShortString()`（`AccessibilityServiceInfo.java:946`）→ `包名/.类名` 的**短式**，
     * 这里已归一化，两个函数的结果可以直接 `equals` / `contains` 互相比较。
     * ⚠ feedbackType 掩码：这里传的 `FEEDBACK_ALL_MASK`（`0xFFFFFFFF`，`AccessibilityServiceInfo.java:205`）
     * **等于完全不过滤** —— 服务端（`AccessibilityManagerService.getEnabledAccessibilityServiceList`）的判定是
     *
     * ```
     * if ((service.mFeedbackType & feedbackType) != 0
     *         || feedbackType == AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
     * ```
     *
     * 第二条是**显式通配分支**，所以连 `feedbackType = 0` 的服务也照样返回
     * （`android:accessibilityFeedbackType` 缺省值就是 0，`AccessibilityServiceInfo.java:746-748`）。
     * ⚠ 该通配分支 **Android 14 才加入**（已核实：android-13.0.0_r1 只有位与测试，android-14.0.0_r1 有），
     * 在 13 及更早的设备上 `feedbackType = 0` 的服务会被静默漏掉 —— 名单读取没有这个条件。
     * ⚠ 另一道闸：注册了 UiAutomation 且 connect flags **未含**
     * `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`(0x1) 时，`suppressingAccessibilityServicesLocked()`
     * 为 true → 本方法被直接短路成 `Collections.emptyList()`，与「一个服务都没启用」无法区分。
     * 本库连 UiAutomation 走的是 `connect(int)` 分支并传了 0x1（`InvisibleAutomation`），故不受影响；
     * 只有回退到无参 `connect()`（flags = 0）时才会踩到。
     */
    @JvmOverloads
    @JvmStatic
    fun getEnabledServiceIds(context: Context = appContext): List<String> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.id }
            .map { it.toCanonicalComponent() }
    }

    /**
     * secure 名单里的无障碍服务组件（`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`）。
     *
     * 刻意**只走 ContentResolver**，不借 [AccessibilityEnableHelper.listEnabledAccessibilityServices]
     * 的 shell 优先路径，两个理由：
     *  ① 它是 [whiteAccessibilityIsEnabled] 的**第二个独立来源**，若两条链路都依赖同一次 shell 调用，
     *     交叉验证就失去意义（shell 出问题时会一起错）；
     *  ② 这个判断可能落在热路径上，而每次 shell exec 都要 fork 一个 sh，比读 ContentResolver 贵得多。
     * 解析统一交给 [AccessibilityEnableHelper.parseEnabledServices]（trim / 空项处理与库内其它地方一致），
     * 再按 [toCanonicalComponent] 归一化成与 [getEnabledServiceIds] **相同的长式**
     * （`ComponentName.flattenToString()`），两个函数的返回值可以直接互相比较。
     *
     * ⚠ 返回的是「名单原文」，**不代表服务此刻正在运行** —— 名单 + 总开关两者缺一不可。
     */
    @JvmOverloads
    @JvmStatic
    fun getEnabledServiceIdsFromSecure(context: Context = appContext): List<String> {
        val raw = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return AccessibilityEnableHelper.parseEnabledServices(raw).map { it.toCanonicalComponent() }
    }

    /**
     * 把无障碍服务标识归一化成**长式** `包名/包名.类名`（`ComponentName.flattenToString()`）。
     *
     * 两个来源的原始形态不同，必须归一化后才能互相比较：
     * - [getEnabledServiceIds]（AMS）：`getId()` = `flattenToShortString()` → `包名/.类名`（短式，
     *   类名与包名同前缀时会被压掉）；
     * - [getEnabledServiceIdsFromSecure]：secure 名单里存的就是长式，但可能来自第三方工具而形态不一。
     *
     * 实现委托给 [AccessibilityEnableHelper.canonicalComponent]（全库唯一入口，写路径的比较也用它）。
     */
    private fun String.toCanonicalComponent(): String =
        AccessibilityEnableHelper.canonicalComponent(this)

    private fun isAnyInWhiteList(ids: List<String>, whiteList: List<String>): Boolean {
        Log.d(TAG, "白名单列表 = $whiteList")
        Log.d(TAG, "当前启用服务 = $ids")
        var hit = false
        for (id in ids) {
            for (name in whiteList) {
                if (name.isNotEmpty() && id.contains(name)) {
                    Log.w(TAG, "命中! id=$id  ←  白名单特征=$name")
                    hit = true
                }
            }
        }
        return hit
    }
}
