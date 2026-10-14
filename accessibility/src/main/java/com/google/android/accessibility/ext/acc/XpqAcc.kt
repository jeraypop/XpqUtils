package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.acc.XpqAcc.connectUiAutomation
import com.google.android.accessibility.ext.acc.XpqAcc.use
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.AliveUtils.showCheckDialog
import com.google.android.accessibility.ext.utils.LibCtxProvider
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.MMKVUtil
import com.google.android.accessibility.ext.utils.NotificationUtilXpq
import com.google.android.accessibility.ext.utils.safecheck.AccIsOpenUtil
import com.google.android.accessibility.ext.window.DynamicIslandFloatWindow
import com.google.android.accessibility.notification.AppExecutors
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.google.android.accessibility.selecttospeak.accessibilityServiceLiveData
import com.google.android.accessibility.uiautomation.engine.InvisibleAutomation
import com.google.android.accessibility.uiautomation.shizuku.AutomationShizuku
import com.google.android.accessibility.uiautomation.shizuku.ShizukuProviderGuard

/**
 * 无障碍能力门面：宿主在 App 启动时一次性调用 [use] 选择通道（无障碍 / UiAutomation），
 * 之后业务代码零改动。
 *
 * 切换原理：UiAutomation 模式下把全局 accessibilityService 指向 [ProxyAccessibilityService]
 * （覆盖取根/窗口/焦点委托给 UiAutomation），无障碍模式下恢复真实 service 实例。
 * 节点查询/点击天然跨通道，performGlobalAction / dispatchGesture 由库内 3 处封装点走本门面。
 */
object XpqAcc {

    /** Shizuku 不可用真因①：Shizuku 本身没在运行。文案与历史一致，别改（外部按字符串判断过）。 */
    private const val REASON_SHIZUKU_NOT_RUNNING = "Shizuku 未运行"

    /** Shizuku 不可用真因②：本应用的 ShizukuProvider 组件被外部禁用（与 Shizuku 开没开无关）。 */
    private const val REASON_SHIZUKU_COMPONENT_DISABLED = "Shizuku 组件被禁用"

    private val a11yDriver: AccDriver = A11yDriver
    private val uiDriver: AccDriver = UiAutomationDriver
    private val proxyService by lazy {
        ProxyAccessibilityService().apply { ensureAppContext() }
    }

    /** 事件桥接的宿主服务实例（UiAutomation 模式手动 new），用于断开时触发销毁回调释放资源。 */
    @Volatile
    private var bridgedHandler: SelectToSpeakServiceAbstract? = null

    /**
     * 宿主注册的桥接实例工厂。宿主未在清单声明 BIND_ACCESSIBILITY_SERVICE 服务时，
     * 反射找不到子类，此时由 [autoBridgeAccessibilityEvent] 从该工厂取兜底实例（通常 `{ SelectToSpeakService() }`）。
     */
    @Volatile
    private var bridgeFallbackProvider: (() -> SelectToSpeakServiceAbstract?)? = null

    /** 宿主注册桥接实例工厂，供库在无法反射到无障碍服务子类时兜底创建实例。 */
    @JvmStatic
    fun setBridgeFallbackProvider(provider: (() -> SelectToSpeakServiceAbstract?)?) {
        bridgeFallbackProvider = provider
    }

    /** 断开 UiAutomation 连接时统一收尾：注销进程级回调、触发宿主销毁回调并清引用。 */
    private fun teardownUiAutomationBridge() {
        unregisterUiAutomationComponentCallbacks()
        val h = bridgedHandler
        bridgedHandler = null
        h?.let { runCatching { it.onUiAutomationDestroy() } }
    }

    /**
     * 进程级「配置变化」回调 —— UiAutomation 模式下 `onConfigurationChanged` 的唯一入口。
     *
     * 系统只对**被 attach 的 Service** 回调 `onConfigurationChanged`：UiAutomation 模式下 App 进程内
     * 没有这样的实例（`proxyService` 与桥接实例都是手动 new，不在 `ActivityThread` 的 mServices 里），
     * 宿主写在无障碍服务里的 override 等于死代码 —— 旋转后没有任何适配，而且**不报错**。
     *
     * `Context.registerComponentCallbacks` 内部落到 **Application（进程级）**，与有没有 Service 实例无关；
     * 收到后转发给**桥接实例**（宿主自己的类，桥接前已 ensureAppContext），于是宿主只需 override 一次
     * 无障碍服务的 `onConfigurationChanged`，两种模式同时生效（那个 override 以前在纯 Shizuku 模式下是死的）。
     *
     * 注册/注销与 [bridgedHandler] 严格配对：见 [autoBridgeAccessibilityEvent] / [teardownUiAutomationBridge]。
     * 注册到 Application 是**进程级**的，漏注销会泄漏且断开连接后仍在回调，务必成对。
     *
     * ⚠ 刻意**不订阅** `onTrimMemory`：① 现代 Android 上它很稀疏 —— API 34 起 5 个档位
     * （RUNNING_MODERATE / RUNNING_LOW / RUNNING_CRITICAL / MODERATE / COMPLETE）不再派发、
     * `onLowMemory()` 永不调用，前台型进程连
     * `TRIM_MEMORY_BACKGROUND` 都被 `handleTrimMemory` 吞掉，实际只剩 `TRIM_MEMORY_UI_HIDDEN`；
     * ② 那个档位宿主从 `Application` / `Activity` 的 `onTrimMemory` 本来就能直接拿到，本库没有
     * 需要它的消费方。所以这里注册 `ComponentCallbacks` 而非 `ComponentCallbacks2`
     * （`Application.dispatchTrimMemory` 会按 `instanceof ComponentCallbacks2` 过滤 —— 类型上就断开了）。
     * 将来真有需要时换回 `ComponentCallbacks2` 并加一条转发即可。
     */
    private val uiAutomationComponentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            forwardToBridge { it.onConfigurationChanged(newConfig) }
        }

        // ComponentCallbacks 的必需成员。API 34 起系统已不再调用它（ComponentCallbacks 源码注释原文：
        // "Since API level 34 this is never called"），Application.dispatchLowMemory 只会打到这里，故留空。
        // 父接口已把该方法标记 @Deprecated，故显式抑制 OVERRIDE_DEPRECATION。
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() {}
    }

    /** [uiAutomationComponentCallbacks] 是否已注册（幂等用，避免进程级重复注册）。 */
    @Volatile
    private var componentCallbacksRegistered = false

    /**
     * 把进程级回调转发给宿主桥接实例。
     *
     * 双活去重：宿主无障碍服务真实在跑（`KEY_UIA_ENABLE_HOST_ACC=true`）时，系统自己会把
     * `onConfigurationChanged` 回调到那个**真实实例**上，这里再转一次宿主会收到两遍。
     */
    private fun forwardToBridge(action: (SelectToSpeakServiceAbstract) -> Unit) {
        if (SelectToSpeakServiceAbstract.instance != null) return
        val h = bridgedHandler ?: return
        runCatching { action(h) }
    }

    /** 注册进程级回调（幂等：重复调用先注销旧的）。 */
    private fun registerUiAutomationComponentCallbacks() {
        val ctx = runCatching { appContext }.getOrNull() ?: return
        unregisterUiAutomationComponentCallbacks()
        runCatching { ctx.registerComponentCallbacks(uiAutomationComponentCallbacks) }
            .onSuccess { componentCallbacksRegistered = true }
    }

    /** 注销进程级回调（幂等）。 */
    private fun unregisterUiAutomationComponentCallbacks() {
        if (!componentCallbacksRegistered) return
        componentCallbacksRegistered = false
        val ctx = runCatching { appContext }.getOrNull() ?: return
        runCatching { ctx.unregisterComponentCallbacks(uiAutomationComponentCallbacks) }
    }

    @Volatile
    @JvmStatic
    var driver: AccDriver = a11yDriver
        private set

    @JvmStatic
    val mode: EngineMode get() = driver.mode

    @JvmStatic
    val isConnected: Boolean get() = driver.isConnected

    // ---- Shizuku shell / 无障碍服务直接开启（详见 AccessibilityEnableHelper） ----

    /** shell 通道是否可用（UserService 绑定后即 true，无需 UiAutomation 注册成功）。 */
    @JvmStatic
    fun isShellAvailable(): Boolean = AccessibilityEnableHelper.isShellAvailable()

    /** 执行一条 shell 命令（uid 2000，等价 adb shell，不带 "adb shell" 前缀；阻塞，勿主线程调）。未绑定返回 null。 */
    @JvmStatic
    fun execShell(command: String) = AccessibilityEnableHelper.execShell(command)

    /**
     * 直接开启某个无障碍服务（无需用户去系统设置点亮）：写名单 + 总开关置 1 + 回读校验。
     * 自动选路径：Shizuku shell（零授权）→ WRITE_SECURE_SETTINGS（宿主被 adb grant 过）。
     * 阻塞，勿主线程调用。
     *
     * @param exclusive false（默认）= 纯增量，追加名单不影响已开启的其它服务；
     *                  true = 独占，关掉其它所有服务只保留传入的这个。
     */
    @JvmStatic
    @JvmOverloads
    fun enableAccessibilityService(
        flatComponent: String,
        context: Context = appContext,
        exclusive: Boolean = false
    ): AccessibilityEnableHelper.EnableResult =
        AccessibilityEnableHelper.enableAccessibilityService(context, flatComponent, exclusive)

    /** 关闭指定无障碍服务（从名单移除；名单清空时总开关一并置 0）。阻塞，勿主线程调用。 */
    @JvmStatic
    @JvmOverloads
    fun disableAccessibilityService(
        flatComponent: String,
        context: Context = appContext
    ): AccessibilityEnableHelper.EnableResult =
        AccessibilityEnableHelper.disableAccessibilityService(context, flatComponent)

    /** 切换通道；切换时先主动关闭旧通道，再切换到新通道并同步 accessibilityService 全局变量指向。 */
    @JvmStatic
    fun use(mode: EngineMode) {
        if (driver.mode == mode) return
        // 主动关闭旧通道，避免无障碍服务与 UiAutomation 并存干扰
        when (driver.mode) {
            EngineMode.ACCESSIBILITY_SERVICE -> {
                // 从无障碍切换到其它模式：主动禁用无障碍服务（disableSelf），
                // 否则服务仍在后台监听事件/执行点击，与 UiAutomation 并存会产生干扰。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching { SelectToSpeakServiceAbstract.instance?.disableSelf() }
                }
            }
            EngineMode.UIAUTOMATION -> {
                teardownUiAutomationBridge()
                runCatching { driver.disconnect() }
            }
        }
        driver = when (mode) {
            EngineMode.ACCESSIBILITY_SERVICE -> a11yDriver
            EngineMode.UIAUTOMATION -> uiDriver
        }
        val newValue: AccessibilityService? = when (mode) {
            EngineMode.ACCESSIBILITY_SERVICE -> SelectToSpeakServiceAbstract.instance
            EngineMode.UIAUTOMATION -> proxyService.also { it.ensureAppContext() }
        }
        // setValue 要求主线程；后台线程调用时降级为 postValue，避免崩溃
        if (Looper.myLooper() == Looper.getMainLooper()) {
            accessibilityServiceLiveData.value = newValue
        } else {
            accessibilityServiceLiveData.postValue(newValue)
        }
    }

    @JvmStatic
    fun useAccessibilityService() = use(EngineMode.ACCESSIBILITY_SERVICE)

    @JvmStatic
    fun useUiAutomation() = use(EngineMode.UIAUTOMATION)

    // ---- Shizuku 检测 / 授权（UiAutomation 通道的前置步骤）----

    /**
     * Shizuku 是否在运行（未运行则 UiAutomation 通道无法连接，需引导用户先启动 Shizuku）。
     *
     * 注意：返回 `false` **不等于**「Shizuku 没打开」。它只是 `Shizuku.pingBinder() == false` 的同义词，
     * 成因至少两种、处置完全不同，需要区分时用 [shizukuUnavailableReason]：
     * ① Shizuku 确实没在运行；
     * ② 本应用的 `rikka.shizuku.ShizukuProvider` 组件被外部禁用——此时 server 的
     *    `getContentProviderExternal("<pkg>.shizuku")` 取不到 provider，只打一行
     *    `E/Service: provider is null <pkg>.shizuku 0` 就放弃，「打开 Shizuku」毫无作用。
     */
    @JvmStatic
    fun isShizukuRunning(): Boolean = AutomationShizuku.isInstalled()

    /**
     * Shizuku 不可用的真因描述；可用时返回 `null`。
     *
     * 存在的意义：`pingBinder() == false` 只是一句话，背后却至少两种成因。日志与弹窗都要说清是哪一种，
     * 否则排障时只能看到「Shizuku 未运行」——既可能是用户没开 Shizuku，也可能是组件被设备上的
     * 组件管理/精简类工具禁掉了，后者重启 Shizuku 不管用。
     */
    @JvmStatic
    fun shizukuUnavailableReason(): String? = when {
        isShizukuRunning() -> null
        ShizukuProviderGuard.isDisabled(appContext) -> REASON_SHIZUKU_COMPONENT_DISABLED
        else -> REASON_SHIZUKU_NOT_RUNNING
    }

    /** 本 App 是否已获得 Shizuku 授权。 */
    @JvmStatic
    fun isShizukuPermissionGranted(): Boolean = AutomationShizuku.isPermissionGranted()

    /** 请求 Shizuku 授权（Shizuku 会弹出授权界面），结果经回调返回 granted 是否成功。 */
    @JvmStatic
    fun requestShizukuPermission(onResult: (granted: Boolean) -> Unit) =
        AutomationShizuku.requestPermission(onResult)

    val WRITE_SECURE = "pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS"
    val SELECTTOSPEAK = "com.google.android.marvin.talkback/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
    /**
     * 一步到位：切 UiAutomation + 检测/请求 Shizuku + 连接。
     *
     * 连接本身是阻塞操作（绑定 Shizuku UserService 最多等 10s、UiAutomation.connect 最多等 5s），
     * 故本方法内部在后台线程执行连接，onLog / onResult 都切回主线程回调，宿主可放心在主线程调用。
     *
     * 连接成功后是否**增量**开启【宿主自己】的无障碍服务**没有参数**：唯一数据源是持久化的用户选择
     * `MMKVConst.KEY_UIA_ENABLE_HOST_ACC`（由 [showEngineModeDialog] 里的「同时开启无障碍服务」开关写入）。
     * 组件名反射自宿主清单；传了 [enableServiceOnConnect]（独占开启）时该开关不生效。
     *
     * @param activity 可选；传入时，连接失败会自动弹出引导对话框（而非仅靠 onResult 回调），
     *                 失败原因涉及 Shizuku 时带「打开 Shizuku」按钮。
     * @param bridgeFallback 可选；事件桥接的兜底实例。连接成功后优先反射从 Manifest 实例化宿主的
     *                       无障碍服务子类；反射找不到时，若传了本参数则用它的实例桥接事件。
     *                       不传则跳过（不桥接）。
     */
    @JvmStatic
    @JvmOverloads
    fun connectUiAutomation(
        onLog: (String) -> Unit = {},
        activity: Activity? = null,
        bridgeFallback: SelectToSpeakServiceAbstract? = null,
        /**
         * 连接成功后自动执行的 shell 命令（uid 2000，等价 adb shell，不带 "adb shell" 前缀）。
         * 默认：为宿主申请 WRITE_SECURE_SETTINGS 权限（shell 身份执行 pm grant 合法），
         * 授权后即使 Shizuku 不在，也能走 WRITE_SECURE_SETTINGS 路径开启服务。传 null = 不执行。
         */
        postConnectShellCommand: String? = WRITE_SECURE,
        /**
         * 连接成功后自动开启的无障碍服务（展平组件名，如 "com.host/.MyAccService"）。null = 不开启。
         *
         * 开启方式固定为**独占**：会关掉设备上其它已开启的无障碍服务，只保留本参数指定的服务。
         * （原 `enableServiceOnConnectExclusive` 开关已移除，不再支持增量追加。）
         */
        enableServiceOnConnect: String? = null,
        /**
         * 连接成功后是否自动关闭【宿主自己】的无障碍服务。
         *
         * 用途：切到 UiAutomation 通道后，宿主无障碍服务若仍在运行，无障碍通道与 UiAutomation 通道会同时执行
         * 业务（双跑）。置 true 可在连接成功后把它关掉。只动宿主自己的服务，不影响设备上其它应用的无障碍服务。
         *
         * 注意：这与 [use] 里跨进程拿不到 `instance` 而失效的 `disableSelf()` 不同——这里走
         * [AccessibilityEnableHelper] 的 shell / WRITE_SECURE_SETTINGS 路径写系统名单，跨进程有效。
         */
        disableHostAccessibilityService: Boolean = false,
        onResult: (success: Boolean, reason: String?) -> Unit = { _, _ -> },
    ) {
        use(EngineMode.UIAUTOMATION)
        val main = Handler(Looper.getMainLooper())
        val log: (String) -> Unit = { s -> main.post { onLog(s) } }
        // 失败统一收尾：回调 onResult + （传了 activity 时）弹引导对话框
        val fail: (String) -> Unit = { reason ->
            main.post {
                onResult(false, reason)
                activity?.let { showUiAutomationFailDialog(it, reason) }
            }
        }
        // 连接成功后的统一收尾：触发就绪初始化（如灵动岛，等价无障碍 onServiceConnected）+ 回调结果
        val finishConnect: (Boolean) -> Unit = { ok ->
            if (ok) {
                runCatching { DynamicIslandFloatWindow.autoInit() }
                runCatching { autoBridgeAccessibilityEvent(bridgeFallback) }
                // 连接成功后自动执行的 shell 命令（可选）。此时 shell UserService 已绑定（uid 2000），
                // 本身就在后台线程，exec 的 binder 调用 + waitFor 阻塞安全；失败不影响连接成功回调。
                postConnectShellCommand?.takeIf { it.isNotBlank() }?.let { cmd ->
                    runCatching {
                        val r = InvisibleAutomation.exec(cmd)
                        if (r != null && r.exitCode == 0) {
                            log("shell 命令执行成功: $cmd${if (r.stdout.isNotBlank()) " → ${r.stdout.trim().take(200)}" else ""}")
                        } else {
                            log("✗ shell 命令执行失败: exit=${r?.exitCode}, ${r?.stderr?.trim()?.take(200)}")
                        }
                    }.onFailure { log("✗ shell 命令执行异常: ${it.message}") }
                }
                // 连接成功后自动开启指定无障碍服务（可选）：名单写入 + 总开关置 1 + 回读校验，自动选 shell / WRITE_SECURE_SETTINGS 路径。
                // 固定「独占」方式（exclusive = true）：替换整机无障碍名单，只保留该服务。
                //字符串非 null、非空且不全是空白字符时，才会执行后续逻辑
                enableServiceOnConnect?.takeIf { it.isNotBlank() }?.let { flat ->
                    runCatching {
                        val r = AccessibilityEnableHelper.enableAccessibilityService(
                            appContext, flat, true
                        )
                        val via = if (r.viaShell) "shell" else "WRITE_SECURE_SETTINGS"
                        val mode = "独占"
                        if (r.success) log("✅ 已自动开启无障碍服务 $flat（$via/$mode）: ${r.message}")
                        else log("✗ 自动开启无障碍服务 $flat 失败（$via/$mode）: ${r.message}")
                    }.onFailure { log("✗ 自动开启无障碍服务异常: ${it.message}") }
                }
                // 连接成功后自动开启【宿主自己】的无障碍服务（可选）：组件名反射自宿主清单，增量追加、不影响其它服务。
                // 取值来自持久化的用户开关（弹窗里的「同时开启无障碍服务」），不再有参数。
                // 传了 enableServiceOnConnect（独占开启）或 disableHostAccessibilityService 时，本步不生效。
                val enableHostAcc = MMKVUtil.get(MMKVConst.KEY_UIA_ENABLE_HOST_ACC, false)
                if (enableHostAcc && !disableHostAccessibilityService && enableServiceOnConnect.isNullOrBlank()) {
                    runCatching {
                        val flat = hostAccessibilityServiceFlat()
                        if (flat == null) {
                            log("✗ 自动开启宿主无障碍服务失败：宿主清单未声明 BIND_ACCESSIBILITY_SERVICE 服务")
                        } else {
                            val r = AccessibilityEnableHelper.enableAccessibilityService(appContext, flat, false)
                            val via = if (r.viaShell) "shell" else "WRITE_SECURE_SETTINGS"
                            if (r.success) log("✅ 已自动开启宿主无障碍服务 $flat（$via/增量）: ${r.message}")
                            else log("✗ 自动开启宿主无障碍服务 $flat 失败（$via/增量）: ${r.message}")
                        }
                    }.onFailure { log("✗ 自动开启宿主无障碍服务异常: ${it.message}") }
                }
                // 连接成功后自动关闭【宿主自己】的无障碍服务（可选）：避免无障碍通道与 UiAutomation 通道双跑。
                if (disableHostAccessibilityService) {
                    runCatching {
                        val flat = hostAccessibilityServiceFlat()
                        if (flat == null) {
                            log("✗ 自动关闭宿主无障碍服务失败：宿主清单未声明 BIND_ACCESSIBILITY_SERVICE 服务")
                        } else {
                            val r = AccessibilityEnableHelper.disableAccessibilityService(appContext, flat)
                            val via = if (r.viaShell) "shell" else "WRITE_SECURE_SETTINGS"
                            if (r.success) log("✅ 已自动关闭宿主无障碍服务 $flat（$via）: ${r.message}")
                            else log("✗ 自动关闭宿主无障碍服务 $flat 失败（$via）: ${r.message}")
                        }
                    }.onFailure { log("✗ 自动关闭宿主无障碍服务异常: ${it.message}") }
                }
                main.post { onResult(true, null) }
            } else {
                fail(UiAutomationDriver.lastError ?: "连接失败")
            }
        }
        Thread {
            // 连接前先清理旧连接，避免 system_server 残留注册导致 "already registered" 失败
            runCatching { disconnect() }
            if (!isShizukuRunning()) {
                // 「Shizuku 未运行」只是 pingBinder()==false 的同义词，成因至少两种，处置完全不同：
                // ① Shizuku 真没在跑 → 引导用户去开 Shizuku；
                // ② 本应用的 rikka.shizuku.ShizukuProvider 组件被外部禁用 → server 的
                //    getContentProviderExternal("<pkg>.shizuku") 返回 null，它只打一行
                //    "provider is null <pkg>.shizuku 0" 就放弃（不重试、不报错），此时
                //    「打开 Shizuku」毫无作用，必须把组件恢复回来 + 重启应用。
                if (ShizukuProviderGuard.isDisabled(appContext)) {
                    // App 改自己的组件不需要任何权限；恢复后本进程仍拿不到 binder（server 那轮投递已失败），
                    // 需重启应用让 server 重新投递，见 ShizukuProviderGuard 类注释「关于生效时机」。
                    val fixed = ShizukuProviderGuard.ensureEnabled(appContext) { log(it) }
                    val tip = if (fixed) "；已自动恢复，请重新打开应用后重试"
                    else "; 自动恢复失败，需手工执行 adb shell pm enable " +
                        "${appContext.packageName}/rikka.shizuku.ShizukuProvider"
                    log("✗ Shizuku 通道不可用：本应用的 rikka.shizuku.ShizukuProvider 组件被禁用" +
                        "（并非 Shizuku 没打开）$tip")
                    fail(REASON_SHIZUKU_COMPONENT_DISABLED)
                } else {
                    log("✗ Shizuku 未运行，请先启动 Shizuku")
                    fail(REASON_SHIZUKU_NOT_RUNNING)
                }
                return@Thread
            }
            if (!isShizukuPermissionGranted()) {
                log("Shizuku 未授权，请求授权中…")
                // 授权弹窗需在主线程
                main.post {
                    requestShizukuPermission { granted ->
                        if (granted) {
                            log("Shizuku 授权成功，开始连接…")
                            Thread { finishConnect(connect(log)) }.start()
                        } else {
                            log("✗ Shizuku 授权被拒绝")
                            fail("Shizuku 授权被拒绝")
                        }
                    }
                }
                return@Thread
            }
            finishConnect(connect(log))
        }.start()
    }

    // ---- 门面透传 ----
    @JvmStatic
    @JvmOverloads
    fun connect(onLog: (String) -> Unit = {}) = driver.connect(onLog)

    @JvmStatic
    fun disconnect() {
        teardownUiAutomationBridge()
        driver.disconnect()
    }

    /**
     * 探测 system_server 是否已有 UiAutomation 注册（被其它 App/进程占用）。
     * 仅 UiAutomation 通道有意义；借 shell `dumpsys accessibility` 判断，未注册/失败均返回 false。
     * 注意：只能判断"是否被占用"，无法得知占用者是谁。
     */
    @JvmStatic
    fun isUiAutomationOccupied(): Boolean =
        if (driver is UiAutomationDriver) (driver as UiAutomationDriver).isUiAutomationOccupied() else false

    @JvmStatic
    fun rootInActiveWindow(): AccessibilityNodeInfo? = driver.rootInActiveWindow()

    @JvmStatic
    fun windows(): List<AccessibilityWindowInfo>? = driver.windows()

    @JvmStatic
    fun findFocus(focusType: Int): AccessibilityNodeInfo? = driver.findFocus(focusType)

    @JvmStatic
    fun performGlobalAction(action: Int): Boolean = driver.performGlobalAction(action)

    @JvmStatic
    fun dispatchGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?
    ): Boolean = driver.dispatchGesture(gesture, callback, handler)

    /**
     * 读取当前通道的 [AccessibilityServiceInfo]。
     * 等价 `AccessibilityService.getServiceInfo()`，但该方法是 final、代理实例无法覆盖，故收敛到本门面。
     * 无障碍通道 = 真实服务的 serviceInfo；UiAutomation 通道 = null。
     */
    @JvmStatic
    fun getServiceInfo(): AccessibilityServiceInfo? = driver.getServiceInfo()

    /**
     * 写入 [AccessibilityServiceInfo]，等价 `AccessibilityService.setServiceInfo()`。
     * 无障碍通道 = 真实服务；UiAutomation 通道 = 无效（事件订阅由 system_server 侧注册决定）。
     */
    @JvmStatic
    fun setServiceInfo(info: AccessibilityServiceInfo?) = driver.setServiceInfo(info)

    /**
     * 关闭自身，等价 `AccessibilityService.disableSelf()`。
     * 无障碍通道 = 真实服务 disableSelf；UiAutomation 通道 = 断开 UiAutomation 连接。
     */
    @JvmStatic
    fun disableSelf() = driver.disableSelf()

    /**
     * 输入文本。[clearFirst]=true 时输入前先清空原内容（两通道语义对齐：
     * 无障碍走节点级 SET_TEXT ""，UiAutomation 走 shell 全选+DEL）。
     */
    @JvmStatic
    @JvmOverloads
    fun inputText(node: AccessibilityNodeInfo?, text: String, clearFirst: Boolean = true): Boolean =
        driver.inputText(node, text, clearFirst)

    @JvmStatic
    @JvmOverloads
    fun inputTextPaste(
        node: AccessibilityNodeInfo?,
        byClipboard: Boolean,
        text: String,
        clearFirst: Boolean = true
    ): Boolean =
        driver.inputTextPaste(node, byClipboard, text, clearFirst)

    /** 延迟输入，[clearFirst] 语义同 [inputText]。 */
    @JvmStatic
    @JvmOverloads
    fun inputTextNew(node: AccessibilityNodeInfo?, text: String, clearFirst: Boolean = true): Boolean =
        driver.inputTextNew(node, text, clearFirst)

    /**
     * 切换坐标点击（tap）的注入方式：
     * - true  = 反射 InputManager.injectInputEvent（pressure/size 拟人起伏）
     * - false = shell `input swipe`（机器特征明显）
     */
    @JvmStatic
    fun setTapReflectMode(enabled: Boolean) {
        com.google.android.accessibility.uiautomation.engine.InvisibleAutomation.tapReflectMode = enabled
    }

    @JvmStatic
    fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?) =
        driver.setOnAccessibilityEventListener(listener)

    /**
     * 返回当前可用的 service 实例，**真实无障碍服务优先，与当前通道无关**：
     * - 真实无障碍服务已被系统绑定（`SelectToSpeakServiceAbstract.instance != null`，含
     *   「无障碍 + UiAutomation 双活」）→ 返回该真实实例（有 context / serviceInfo，final 动作可用）；
     * - 否则 → 返回 [ProxyAccessibilityService]（非 null，取根/窗口/焦点委托给 [XpqAcc] 当前通道）。
     *
     * 用于给 XPQEventData.service 等需要 AccessibilityService 的地方提供值。
     *
     * 注意：本方法**只决定返回哪个实例，不改变点击/手势的通道**——手势仍由 [driver] 决定
     * （即 [dispatchGesture] 等门面方法）。但拿到真实实例后直接调 `service.xxx()` 会走真实无障碍服务
     * （`getRootInActiveWindow` / `dispatchGesture` / `performGlobalAction` 都是真实实现），
     * 所以取根与点击请统一走 [XpqAcc] 门面，本方法供「需要真实 AccessibilityService 对象」的场景使用。
     */
    @JvmStatic
    fun currentService(): AccessibilityService? =
        SelectToSpeakServiceAbstract.instance ?: proxyService.also { it.ensureAppContext() }

    /**
     * 事件桥接：把当前通道的无障碍事件转发给指定的事件处理器（宿主继承 [SelectToSpeakServiceAbstract] 的实例）。
     * 直接调用 [SelectToSpeakServiceAbstract.onAccessibilityEvent]（多态分派），宿主 override 的 onAccessibilityEvent 也会执行；
     * 基类实现内部会 dealEvent → asyncHandle_XXX + asyncHandleAccessibilityEvent + listeners，与无障碍模式等价。
     * 无障碍模式无需调用（系统直接回调 onAccessibilityEvent）；UiAutomation 模式下手动 new 一个服务实例传入即可。
     */
    @JvmStatic
    fun bridgeAccessibilityEvent(handler: SelectToSpeakServiceAbstract) {
        // 兜底：宿主自行 new 实例调用本方法时同样补齐 Context 能力（autoBridge 路径已在更早处
        // 调用过，[ensureAppContext] 幂等，重复调用无副作用）。
        runCatching { handler.ensureAppContext() }
        setOnAccessibilityEventListener { event ->
            // 事件回调发生在 UiAutomation 线程（InvisibleAutoThread）；handler.onAccessibilityEvent
            // → dealEvent 内是跨进程 getRoot + copyNodeCompat 遍历整棵树的【重活】。若在此线程同步执行，
            // 会阻塞 UiAutomation 回调线程，进而拖慢主线程业务的 UiAutomation 同步接口 → ANR。
            // 改为提交到后台单线程池：保持事件顺序，且不阻塞回调线程（重活与系统回调解耦）。
            AppExecutors.executors5.execute {
                runCatching { handler.onAccessibilityEvent(event) }
            }
        }
    }

    /**
     * 自动事件桥接：从宿主 App 的 Manifest 反射找到声明了 `BIND_ACCESSIBILITY_SERVICE` 的
     * 无障碍服务子类并实例化，再桥接事件。宿主无需手动 new + bridgeAccessibilityEvent。
     *
     * 反射找不到子类时，若传了 [fallback] 则用它桥接；否则返回 false。
     * 已在 [connectUiAutomation] 连接成功后自动调用；宿主也可手动调用本方法。
     * 找不到子类且无兜底时返回 false（不抛异常，不影响连接本身）。
     */
    @JvmStatic
    @JvmOverloads
    fun autoBridgeAccessibilityEvent(fallback: SelectToSpeakServiceAbstract? = null): Boolean {
        val handler = findAccessibilityServiceSubclass() ?: fallback ?: bridgeFallbackProvider?.invoke()
        if (handler == null) {
            android.util.Log.w("XpqAcc", "自动事件桥接失败：未找到宿主无障碍服务子类，且无兜底实例")
            return false
        }
        // 手动 new 的实例不会被系统绑定（onServiceConnected 不回调），这里手动触发宿主的
        // 就绪钩子，把通道无关的初始化（如屏幕广播接收器）补上。
        // service 传 currentService()：真实无障碍服务在绑定时给它、否则给 proxyService。
        // 同时保存引用，供断开连接时触发 onUiAutomationDestroy 释放资源。
        bridgedHandler = handler
        // 手动 new 的实例从未被系统 attach，ContextWrapper.mBase 为空 → 事件链路里任何一次
        // Context 调用（getString / getSystemService / registerReceiver / packageManager ...）都会 NPE，
        // 且常被上层 catch(Throwable){} 吞掉，外在表现是「什么都没发生」。桥接前统一补齐，让所有
        // 接入本库的宿主一同免疫（含下面 onUiAutomationReady 里宿主自己用到的 Context）。
        runCatching { handler.ensureAppContext() }
        runCatching { handler.onUiAutomationReady(currentService()) }
        bridgeAccessibilityEvent(handler)
        // 系统不会回调「未被 attach 的 Service」的 onConfigurationChanged，
        // 注册到 Application（进程级）后转发给桥接实例 → 宿主两种模式共用一处 override。
        // 注销点 = teardownUiAutomationBridge（必须成对，否则进程级泄漏 + 断开后仍回调）。
        registerUiAutomationComponentCallbacks()
        android.util.Log.i("XpqAcc", "自动事件桥接成功：${handler.javaClass.name}")
        return true
    }

    /** 从宿主 App 的 Manifest 反射实例化声明了 BIND_ACCESSIBILITY_SERVICE 的服务子类。 */
    @JvmStatic
    fun findAccessibilityServiceSubclass(): SelectToSpeakServiceAbstract? {
        return try {
            val ctx = runCatching { LibCtxProvider.Companion.appContext }.getOrNull() ?: return null
            val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SERVICES)
            val serviceName = pkgInfo.services?.firstOrNull {
                it.permission == android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE
            }?.name ?: return null
            val cls = Class.forName(serviceName)
            val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            instance as? SelectToSpeakServiceAbstract
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 取宿主自己的无障碍服务展平组件名（形如 `pkg/pkg.Cls`），供 [connectUiAutomation] 的
     * 持久化开关（`MMKVConst.KEY_UIA_ENABLE_HOST_ACC`）/ `disableHostAccessibilityService`，
     * 以及宿主自行开关服务时使用。
     * 宿主清单未声明 `BIND_ACCESSIBILITY_SERVICE` 服务、或反射失败时返回 null。
     */
    @JvmStatic
    fun hostAccessibilityServiceFlat(): String? {
        return try {
            val ctx = runCatching { LibCtxProvider.Companion.appContext }.getOrNull() ?: return null
            val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SERVICES)
            val serviceName = pkgInfo.services?.firstOrNull {
                it.permission == android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE
            }?.name ?: return null
            ComponentName(ctx.packageName, serviceName).flattenToString()
        } catch (_: Throwable) {
            null
        }
    }

    // ---- 引擎模式选择 + 持久化 ----

    /** 读取持久化的引擎模式（默认无障碍模式）。 */
    @JvmStatic
    fun loadEngineMode(): EngineMode {
        val saved = MMKVUtil.get(MMKVConst.KEY_ENGINE_MODE, EngineMode.ACCESSIBILITY_SERVICE.ordinal)
        return EngineMode.values().getOrElse(saved) { EngineMode.ACCESSIBILITY_SERVICE }
    }

    /** 持久化引擎模式（仅保存，不切换）。 */
    @JvmStatic
    fun saveEngineMode(mode: EngineMode) {
        MMKVUtil.put(MMKVConst.KEY_ENGINE_MODE, mode.ordinal)
    }

    /**
     * 应用引擎模式：持久化 + 切换通道 + （UiAutomation）自动连接。
     * 结果经 [onResult] 回调：无障碍模式仅切换（需用户已在系统设置开启无障碍服务）；
     * UiAutomation 模式内部会检测/请求 Shizuku 授权并连接。
     *
     * @param bridgeFallback 事件桥接兜底实例，仅 UiAutomation 模式使用；透传给 [connectUiAutomation]。
     * @param disableHostAccessibilityService 仅 UiAutomation 模式使用；连接成功后自动关闭宿主自己的无障碍服务，
     *                                        避免无障碍通道与 UiAutomation 通道双跑。透传给 [connectUiAutomation] 同名参数。
     *                                        （宿主无障碍服务的**开启**由持久化开关决定，见 [connectUiAutomation]。）
     */
    @JvmStatic
    fun applyEngineMode(
        mode: EngineMode,
        bridgeFallback: SelectToSpeakServiceAbstract? = null,
        enableServiceString: String? = null,
        disableHostAccessibilityService: Boolean = false,
        onResult: (success: Boolean, reason: String?) -> Unit = { _, _ -> }
    ) {
        saveEngineMode(mode)
        when (mode) {
            EngineMode.ACCESSIBILITY_SERVICE -> {
                useAccessibilityService()
                val ok = SelectToSpeakServiceAbstract.instance != null
                onResult(ok, if (ok) null else "请先在系统设置开启无障碍服务")
            }
            EngineMode.UIAUTOMATION -> {
                // 全部参数显式写出（取值即默认），行为与隐式省略一致，便于阅读与后续调整。
                connectUiAutomation(
                    onLog = {},
                    activity = null,
                    bridgeFallback = bridgeFallback,
                    postConnectShellCommand = WRITE_SECURE,
                    enableServiceOnConnect = enableServiceString,
                    disableHostAccessibilityService = disableHostAccessibilityService,
                    onResult = onResult
                )
            }
        }
    }

    /**
     * 弹窗选择自动化通道（无障碍 / UiAutomation）。
     *
     * @param onConfirm 无障碍模式下、切换生效后的额外回调，参数为选中的 [EngineMode]。
     *                  切换始终走内置默认逻辑（持久化并立即应用、toast 结果、失败弹窗），不受 onConfirm 影响。
     *                  为 null 时不回调。
     * @param onCancel  点击「取消」后的回调，参数无。
     *                  为 null 时不回调。（注意：点对话框外部 / 按返回键关闭**不会**触发它。）
     * @param pairSuccess 切换**成功**后的回调（无参数）。两种模式成功都会触发（无障碍模式的代码开启成功、
     *                     Shizuku 模式连接成功），与 [pairFail] 对称；调用前库已 toast「已成功切换到 xxx」。
     *                     回调包在 `runCatching` 内，抛出异常不会影响弹窗流程。为 null 时不回调。
     * @param pairFail 切换**失败**后的回调，参数为失败原因（可能为 null）。与 [pairSuccess] 对称，
     *                  两种模式失败都会触发（Shizuku 连接失败、无障碍模式开启失败/未在系统设置开启）。
     *                  仅作通知，不改变内置行为（Shizuku 模式仍弹失败引导对话框、其它情况仍 toast）。
     *                  回调包在 `runCatching` 内，抛异常不影响流程。为 null 时不回调。
     * @param imgRes    无障碍模式跳转设置后弹出的引导对话框图片资源。
     * @param bridgeFallback 事件桥接兜底实例，仅 UiAutomation 模式使用；透传给 [applyEngineMode]。
     * @param enableServiceString 连接成功后要自动开启的无障碍服务组件名（展平形式，独占）。
     *                            非空时（如 [SELECTTOSPEAK]）弹窗内「开启随选朗读」「同时开启无障碍服务」两个开关
     *                            与「2.关闭其它所有的无障碍服务…」提示均不显示，取值完全以本参数为准。
     *                            为 null 时改由弹窗内「开启随选朗读」开关决定（开 = [SELECTTOSPEAK]，关 = 不开启）。
     * @param disableHostAccessibilityService 仅 UiAutomation 模式使用；切换到 Shizuku 模式成功后自动关闭宿主自己的
     *                                        无障碍服务，避免无障碍通道与 UiAutomation 通道双跑。透传给 [applyEngineMode] 同名参数。
     *
     * 宿主无障碍服务的**开启**不需要参数：弹窗内「同时开启无障碍服务」开关即唯一入口，
     * 取值存于 MMKV（`MMKVConst.KEY_UIA_ENABLE_HOST_ACC`），[applyEngineMode] / [connectUiAutomation]
     * 在连接成功后直接读它。
     *
     * 「开启随选朗读」开关（仅 Shizuku 模式下显示）：**等价于**调用方把 [enableServiceString] 传成
     * [SELECTTOSPEAK] —— 为 true 时按「独占开启随选朗读」执行（会关掉设备上其它无障碍服务）；
     * 为 false 时等价于没传（不开启任何无障碍服务）。取值存于 MMKV
     * （`MMKVConst.KEY_SELECTTOSPEAK_ENABLE`，默认 false）。开启它时「同时开启无障碍服务」行会自动隐藏
     * （独占路径下后者的持久化判断本就不生效），说明文字里的「2.关闭其它所有的无障碍服务…」提示也会同步出现。
     *
     * 调用方显式传了 [enableServiceString] 时本行**仍然显示**，但转为只读展示：勾选状态 = 传入的是否为
     * [SELECTTOSPEAK]（传入其它服务时显示为未勾选），**标题与说明文字完全不变**，禁用状态只由开关
     * 自身置灰表达。这样用户始终能看到「本次到底开不开随选朗读」，而不是整行凭空消失。
     *
     * 无障碍模式的「确定」行为按权限分流：
     * - 宿主已获 `WRITE_SECURE_SETTINGS`（连接 Shizuku 时 shell 执行过 `pm grant`）→ 后台线程调
     *   [AccessibilityEnableHelper.enableAccessibilityService] 直接写系统 secure 表开启，
     *   并等系统绑定完成后再切通道，**不弹跳转设置的引导对话框**；
     * - 无该权限，或写入/回读失败，或写入成功但 **3 秒内未绑定成功（超时也算失败）** →
     *   保持原逻辑：切换模式 + 弹引导对话框跳转系统无障碍设置。
     */
    @JvmStatic
    @JvmOverloads
    fun showEngineModeDialog(
        activity: Activity,
        bridgeFallback: SelectToSpeakServiceAbstract? = null,
        imgRes: Int = R.drawable.backgroundshow_xpq,
        onConfirm: ((mode: EngineMode) -> Unit)? = null,//点击确定按钮回调
        onCancel: (() -> Unit)? = null, //点击取消按钮回调
        pairSuccess: (() -> Unit)? = null,  //连接成功后的回调 无障碍模式和uiautomation模式都会回调
        pairFail: ((reason: String?) -> Unit)? = null, //连接失败后的回调  无障碍模式和uiautomation模式都会回调
        enableServiceString: String? = null,
        disableHostAccessibilityService: Boolean = false,
    ) {
        // 清单未声明无障碍服务（tools:node="remove" 或未注册）时，选项仍保留，但切换到无障碍模式会提示不支持
        val hasAccessibility = findAccessibilityServiceSubclass() != null
        val items = arrayOf("无障碍模式", "Shizuku 模式(推荐)")
        val current = loadEngineMode().ordinal

        // 选项下标 -> 实际引擎模式
        fun indexToMode(index: Int): EngineMode =
            if (index == 0) EngineMode.ACCESSIBILITY_SERVICE else EngineMode.UIAUTOMATION

        // 「开启随选朗读」的状态：未传入 enableServiceString 时由弹窗内开关控制，初值取 MMKV 记忆值
        // （默认关，等价于"没传进来"）；显式传入时由调用方决定——开关只读展示（勾选状态 = 传入的是否为
        // [SELECTTOSPEAK]，传了别的服务则显示为未勾选），不受用户操作影响。
        // ⚠ 必须声明在 describeMode 之前：Kotlin 局部函数不能前向引用，describeMode 要读它。
        var selectToSpeakOn = if (enableServiceString != null) enableServiceString == SELECTTOSPEAK
        else MMKVUtil.get(MMKVConst.KEY_SELECTTOSPEAK_ENABLE, false)

        // 调用方显式指定了「独占开启」的服务 → 「开启随选朗读」行只做展示、不参与配置也不可操作。
        val selectToSpeakLocked = enableServiceString != null

        // 本次实际「独占开启」的无障碍服务组件名：显式传入优先，否则看开关
        // （开关开 = 等价于传入 [SELECTTOSPEAK]，关 = 等价于没传）。
        // 决定三处：说明文字里的提示段、【两个开关行的显隐】、以及点「确定」时的实际取值。
        fun curExclusiveService(): String? =
            enableServiceString ?: if (selectToSpeakOn) SELECTTOSPEAK else null

        // 「关闭其它所有的无障碍服务，只保留开启设备自带的随选朗读」这一段只在走独占开启路径时才提示。
        // 自带前后空行，不提示时整段连带空行一起省略，避免多出空行。
        fun closeOtherAccTip(): String =
            if (curExclusiveService() != null) {
                "2.关闭其它所有的无障碍服务，只保留开启设备自带的☞☞： https://settings随选朗读\n\n"
            } else ""

        // 每个模式对应的说明文字，切换选项时动态更新到标题区。
        // Shizuku 模式的下载段按导入形态分流：manager 已并入宿主时无需下载独立 App，
        // 隐藏 3 个下载地址，替换为可点击的「打开 manager 主界面」。
        fun describeMode(mode: EngineMode): String = when {
            mode == EngineMode.ACCESSIBILITY_SERVICE && !hasAccessibility ->
                "无障碍模式\n\n当前版本不支持无障碍模式,如果确定需要无障碍的版本，可下载支持无障碍的版本使用"
            mode == EngineMode.ACCESSIBILITY_SERVICE ->
                "无障碍模式\n\n" +
                        "无需额外安装软件,但是部分软件(比如：银行类软件)检测设备上无障碍服务开启时，可能出现安全提示\n" +
                        "请跳转到☞☞：https://settings.设置 "
            AutomationShizuku.isManagerMergedIntoHost() ->
                "Shizuku 模式(推荐用该模式)\n\n" +
                        "1.本软件已内置 Shizuku 管理器，无需额外下载\n" +
                        "打开 Shizuku 服务☞☞：https://settings.开启Shizuku服务\n\n" +
                        "shizuku 使用教程☞☞：https://mp.weixin.qq.com/s/qG3znEmRgtOkfmktM2mxrA\n\n" +
                        closeOtherAccTip() +
                        "为什么引入该模式：" +
                        "\n由于部分应用会检测设备上启用的第三方无障碍服务，并可能出现安全提示。" +
                        "\n所以，现在采用无障碍模式的应用在wx上已经不推荐再用了，用了被检测到是早几天晚几天的事" +
                        "\nShizuku 模式使用不同的系统权限通道，可以作为另一种自动化方案"
            else ->
                "Shizuku 模式(推荐用该模式)\n\n" +
                        "1.需要额外下载一个免费开源的 Shizuku 软件\n" +
                        "官方原版下载地址☞☞：https://github.com/RikkaApps/Shizuku/releases\n" +
                        "备用原版下载地址☞☞：https://apt.izzysoft.de/fdroid/index/apk/moe.shizuku.privileged.api\n" +
                        "最新修改版下载地址（推荐下载这个）☞☞：https://github.com/thedjchi/Shizuku/releases\n" +
                        "shizuku 使用教程☞☞：https://mp.weixin.qq.com/s/qG3znEmRgtOkfmktM2mxrA\n\n" +
                        closeOtherAccTip() +
                        "为什么引入该模式：" +
                        "\n由于部分应用会检测设备上启用的第三方无障碍服务，并可能出现安全提示。" +
                        "\n所以，现在采用无障碍模式的应用在wx上已经不推荐再用了，用了被检测到是早几天晚几天的事" +
                        "\nShizuku 模式使用不同的系统权限通道，可以作为另一种自动化方案"
        }

        // 说明文字放到标题区（setMessage 与 setSingleChoiceItems 互斥，用了 setMessage 选项列表就不显示）
        val density = activity.resources.displayMetrics.density
        val titleView = TextView(activity).apply {
            textSize = 14f
            // 显式锁定文字颜色：弹窗背景锁定为浅色（见下方 Builder 主题参数），深浅模式下都可读。
            // 部分机型暗黑模式下默认色会解析成浅色压浅底，导致文字看不清。
            setTextColor(0xFF212121.toInt())
            setPadding(
                (20 * density).toInt(),
                (16 * density).toInt(),
                (20 * density).toInt(),
                (8 * density).toInt()
            )
        }

        // 渲染指定模式的说明文字：
        // 无障碍模式 —— 「系统设置 - 无障碍」做成可点击，跳转到无障碍开启界面
        // Shizuku 模式 —— 官方/备用下载地址用 Linkify 识别为可点击跳转
        fun renderMode(index: Int) {
            val mode = indexToMode(index)
            if (mode == EngineMode.ACCESSIBILITY_SERVICE) {
                val text = describeMode(mode)
                val spannable = SpannableString(text)
                val target = "https://settings.设置"
                val start = text.indexOf(target)
                if (start >= 0) {
                    val end = start + target.length
                    // 点击跳转到无障碍开启界面；外观复用系统链接样式（与 Shizuku 下载地址一致）
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            showCheckDialog(
                                activity,
                                R.string.wzaxpq,
                                imgRes,
                                R.string.quanxian0,
                                MMKVConst.BTN_ACCESSIBILITY
                            )
                        }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                titleView.movementMethod = LinkMovementMethod.getInstance()
                titleView.text = spannable
            } else {
                // Shizuku 模式：让「随选朗读」与无障碍模式的「https://settings.设置」同款链接样式，
                // 点击跳转到系统无障碍设置（「随选朗读」开关所在页）；下载地址继续走 Linkify 识别。
                // manager 已并入宿主时，「https://settings打开manager」为占位链接，点击打开宿主内 manager 主界面。
                val text = describeMode(mode)
                val spannable = SpannableString(text)
                val target = "https://settings随选朗读"
                val start = text.indexOf(target)
                if (start >= 0) {
                    val end = start + target.length
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            if (AccIsOpenUtil.isAccessibilityEnabled()){
                                AliveUtils.toast(msg = "随选朗读已开启")
                            }else{
                                AliveUtils.toast(msg = "请开启随选朗读")
                            }
                            showSXLDDialog(activity)
                        }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val managerTarget = "https://settings.开启Shizuku服务"
                val mStart = text.indexOf(managerTarget)
                if (mStart >= 0) {
                    val end = mStart + managerTarget.length
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            if (!AutomationShizuku.openShizuku(activity)) {
                                AliveUtils.toast(msg = "未能打开 Shizuku 管理界面")
                            }
                        }
                    }, mStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                titleView.movementMethod = LinkMovementMethod.getInstance()
                titleView.text = spannable
                Linkify.addLinks(titleView, Linkify.WEB_URLS)
            }
        }
        renderMode(current)

        // 记录当前选中项；点击「确定」时才真正切换并持久化。
        // 提前声明：下面「开启随选朗读」开关的监听里要重渲染说明文字，需要读它。
        var selected = current

        // 「开启随选朗读」开关：等价于调用方把 enableServiceString 传成 [SELECTTOSPEAK]——
        // 开启后切到 Shizuku 模式时会「独占」开启系统的随选朗读（关掉设备上其它无障碍服务）。
        // 调用方显式传了 enableServiceString 时本行仍显示，但转为只读：勾选状态由传入值决定，
        // 只有开关自身置灰（文字颜色不变），表示本次配置由调用方决定、用户改不了。
        val selectToSpeakSwitch = SwitchCompat(activity).apply {
            isChecked = selectToSpeakOn
            isEnabled = !selectToSpeakLocked
        }
        val selectToSpeakTitleView = TextView(activity).apply {
            text = "开启随选朗读"
            textSize = 14f
            // 与标题区同款：显式锁深色，避免暗黑模式下浅色压浅底。
            // 只读与否不改变文字颜色——禁用状态完全由右侧开关自身置灰表达。
            setTextColor(0xFF212121.toInt())
        }
        val selectToSpeakDescView = TextView(activity).apply {
            // 文案与「本次是不是只读」无关：无论配置由谁决定，都展示同一句说明、保持同一颜色。
            text = "开启后，切换到 Shizuku 模式时会一并开启系统的「随选朗读」无障碍服务和关闭 其它所有无障碍(主要使用场景在WX内时，开启本开关)"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
        }
        val selectToSpeakLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(selectToSpeakTitleView)
            addView(selectToSpeakDescView)
        }
        val selectToSpeakRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (20 * density).toInt(),
                (4 * density).toInt(),
                (20 * density).toInt(),
                (4 * density).toInt()
            )
            addView(
                selectToSpeakLabels,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(selectToSpeakSwitch)
        }

        // 「同时开启无障碍服务」开关：唯一控制宿主无障碍服务是否随 Shizuku 模式一并开启。
        // 仅在 Shizuku 模式下有意义（无障碍模式下隐藏）。取值只来自 MMKV 记忆值（默认关闭），
        // 不再接受调用方入参。自建视图用 setView 挂进弹窗的 custom 面板（与单选列表共存）。
        val hostAccSwitch = SwitchCompat(activity).apply {
            isChecked = MMKVUtil.get(MMKVConst.KEY_UIA_ENABLE_HOST_ACC, false)
        }
        val hostAccTitleView = TextView(activity).apply {
            text = "同时开启无障碍服务"
            textSize = 14f
            // 与标题区同款：显式锁深色，避免暗黑模式下浅色压浅底
            setTextColor(0xFF212121.toInt())
        }
        val hostAccDescView = TextView(activity).apply {
            text = "开启后，切换到 Shizuku 模式时会一并打开本应用的无障碍服务(主要使用场景不在WX内时，开启本开关)"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
        }
        val hostAccLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hostAccTitleView)
            addView(hostAccDescView)
        }
        val hostAccRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (20 * density).toInt(),
                (4 * density).toInt(),
                (20 * density).toInt(),
                (8 * density).toInt()
            )
            addView(
                hostAccLabels,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(hostAccSwitch)
        }

        // 两个开关行合并成一个竖直线性布局，作为 setView 的自定义面板内容
        // （setView 只能挂一个 View；面板与单选列表共存，custom 面板在列表上方）
        val switchPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(selectToSpeakRow)
            addView(hostAccRow)
        }

        // 锁定浅色主题：单选列表/按钮/链接色全部固定，不随系统暗黑模式变化（见 titleView 处注释）
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setCustomTitle(titleView)
            // 自定义视图与单选列表分属不同面板，可共存（custom 面板在列表上方）
            .setView(switchPanel)
            .setSingleChoiceItems(items, current) { _, which ->
                selected = which
            }
            .setPositiveButton("确定") { _, _ ->
                val mode = indexToMode(selected)
                // 清单未声明无障碍服务时，不支持切换到无障碍模式，仅提示、不切换
                if (mode == EngineMode.ACCESSIBILITY_SERVICE && !hasAccessibility) {
                    AliveUtils.toast(msg = "当前版本不支持无障碍模式")
                    return@setPositiveButton
                }
                // 本次实际生效的「独占开启」服务：显式传入 enableServiceString 优先，
                // 否则由「开启随选朗读」开关决定（开 = SELECTTOSPEAK，关 = null 即不开启）
                val effectiveEnableServiceString = curExclusiveService()

                // 宿主无障碍服务的开启由持久化开关决定（[connectUiAutomation] 直接读 MMKV，没有参数）。
                // 这里只负责把开关当前取值写回记忆，必须在 applyEngineMode 之前；开关被隐藏时不写，
                // 避免覆盖用户之前的偏好 + 那条路径下它本就不生效：
                //  - 显式传了 enableServiceString → 「随选朗读」行转为只读、「同时开启无障碍服务」行隐藏，
                //    两个开关都不写（配置由调用方决定，不覆盖用户既有偏好）；
                //  - 「随选朗读」开着 → 走独占路径，宿主无障碍行已隐藏，只写「随选朗读」的值。
                if (enableServiceString == null) {
                    MMKVUtil.put(MMKVConst.KEY_SELECTTOSPEAK_ENABLE, selectToSpeakSwitch.isChecked)
                    if (effectiveEnableServiceString == null) {
                        MMKVUtil.put(MMKVConst.KEY_UIA_ENABLE_HOST_ACC, hostAccSwitch.isChecked)
                    }
                }

                // 失败统一通知宿主（与 onUIASuccess 对称，两种模式共用）。
                // 两条失败路径（下面的 handleModeResult / applyModeAndGuideToSetting 的 failToast 分支）
                // 各只调一次，彼此不重叠，宿主不会收到重复回调。
                // ⚠ 必须声明在 handleModeResult / applyModeAndGuideToSetting 之前：局部函数不能前向引用。
                val notifyFail: (String?) -> Unit = { reason ->
                    runCatching { pairFail?.invoke(reason) }
                }

                // 两种分支共用同一份结果处理（applyEngineMode 的 onResult 是命名参数，非尾随 lambda）。
                val handleModeResult: (Boolean, String?) -> Unit = { success, reason ->
                    when {
                        success -> {
                            AliveUtils.toast(msg = "已成功切换到 ${items[selected]}")
                            runCatching {
                                pairSuccess?.invoke()
                            }
                        }
                        mode == EngineMode.UIAUTOMATION -> {
                            notifyFail(reason)
                            showUiAutomationFailDialog(activity, reason)
                            onConfirm?.invoke(mode)
                        }
                        else -> {
                            notifyFail(reason)
                            AliveUtils.toast(msg = reason ?: "切换失败")
                        }
                    }
                }

                // 原有逻辑：切换模式 + 弹「跳转系统无障碍设置」引导对话框。
                // failToast 非空时，用它的文案替代 onResult 里的通用失败提示（调用方更清楚失败原因）。
                fun applyModeAndGuideToSetting(failToast: String? = null) {
                    applyEngineMode(
                        mode = mode,
                        bridgeFallback = bridgeFallback,
                        enableServiceString = effectiveEnableServiceString,
                        disableHostAccessibilityService = disableHostAccessibilityService,
                        onResult = { success, reason ->
                            if (!success && failToast != null) {
                                // 这条路径不走 handleModeResult（用户可见文案换成更具体的 failToast），
                                // 所以失败通知在这里补一次——无论走哪条失败路径，宿主都能收到 onUIAFail。
                                notifyFail(reason)
                                AliveUtils.toast(msg = failToast)
                            } else handleModeResult(success, reason)
                        }
                    )
                    if (mode == EngineMode.ACCESSIBILITY_SERVICE) {
                        onConfirm?.invoke(mode)
                        //NotificationUtilXpq.gotoAccessibilitySetting(activity)
                        showCheckDialog(
                            activity,
                            R.string.wzaxpq,
                            imgRes,
                            R.string.quanxian0,
                            MMKVConst.BTN_ACCESSIBILITY
                        )
                    }
                }

                // 代码开启后等待系统 bindService 的时限与轮询步进（超时按失败处理）
                val readyTimeoutMs = 3_000L
                val readyPollStepMs = 100L

                // 无障碍模式：宿主若已拿到 WRITE_SECURE_SETTINGS（连接 Shizuku 时由 shell 执行过
                // `pm grant`），可直接写系统 secure 表把服务开启，无需用户再去设置页手动点亮；
                // 没有该权限则维持原逻辑（切模式 + 弹跳转设置的引导）。
                val canEnableAccByCode = mode == EngineMode.ACCESSIBILITY_SERVICE &&
                        activity.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                        PackageManager.PERMISSION_GRANTED

                if (canEnableAccByCode) {
                    // 写 secure 表 + 回读校验是阻塞操作，且系统 bindService 是异步的，整体放后台线程；
                    // 等服务实例真的就绪再切通道，否则 applyEngineMode 会因为 instance 仍为 null 而误报失败。
                    Thread {
                        val flat = hostAccessibilityServiceFlat()
                        val result = flat?.let {
                            AccessibilityEnableHelper.enableAccessibilityService(
                                activity.applicationContext, it
                            )
                        }
                        // 写入成功还要等服务被系统绑定；绑定超时一并算失败，退回跳转设置的引导。
                        if (result?.success == true) {
                            var waited = 0L
                            while (SelectToSpeakServiceAbstract.instance == null && waited < readyTimeoutMs) {
                                android.os.SystemClock.sleep(readyPollStepMs)
                                waited += readyPollStepMs
                            }
                        }
                        val ready = result?.success == true && SelectToSpeakServiceAbstract.instance != null
                        activity.runOnUiThread {
                            if (ready) {
                                applyEngineMode(
                                    mode = mode,
                                    bridgeFallback = bridgeFallback,
                                    enableServiceString = effectiveEnableServiceString,
                                    disableHostAccessibilityService = disableHostAccessibilityService,
                                    onResult = handleModeResult
                                )
                                onConfirm?.invoke(mode)
                            } else {
                                applyModeAndGuideToSetting(
                                    failToast = when {
                                        result == null -> "未找到宿主无障碍服务组件，请手动开启"
                                        !result.success -> result.message
                                        else -> "无障碍服务已写入系统设置，但 ${readyTimeoutMs / 1000} 秒内未连接，请手动开启"
                                    }
                                )
                            }
                        }
                    }.start()
                } else {
                    applyModeAndGuideToSetting()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                onCancel?.invoke()
            }
            .setNeutralButton(activity.getString(R.string.sxzxpq)) { _, _ ->
                AliveUtils.shouxianzhi(activity)
            }
            .create()

        // 禁止点外部 / 按返回键关闭：关闭只能通过三个按钮，这样「取消」语义才是完整的
        // （onCancel 只在「取消」按钮里触发，若允许外部关闭，宿主会漏掉一次取消通知）。
        // 注意 setCancelable(false) 已覆盖「点外部关闭」（setCanceledOnTouchOutside 在不可取消时无效）。
        dialog.setCancelable(false)

        // 部分机型的强制深色（Force Dark）会把浅色弹窗局部反转，同样导致文字看不清，显式禁用
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialog.window?.decorView?.isForceDarkAllowed = false
            }
        }

        // 覆盖单选列表默认的「点击即关闭」行为：点击只更新选中项与标题说明，不关闭对话框
        // 「受限制?」按钮仅在无障碍模式下显示（Shizuku 模式无受限设置问题）
        fun updateNeutralVisibility(index: Int) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility =
                if (index == 0) View.GONE else View.GONE //暂时都不显示
        }

        // 两个开关行的显隐（都只对 Shizuku 模式有意义，无障碍模式下整行隐藏）：
        // - 「开启随选朗读」：Shizuku 模式下**始终显示**——调用方显式指定了 enableServiceString 时改为
        //   只读展示（见上方 selectToSpeakLocked），让用户仍能看到「本次到底开不开随选朗读」，
        //   而不是整行凭空消失；
        // - 「同时开启无障碍服务」：本次走独占开启路径时隐藏（两者互斥，同时出现会给出自相矛盾的配置）。
        fun updateSwitchRowVisibility(index: Int) {
            val shizukuMode = index != 0
            selectToSpeakRow.visibility =
                if (shizukuMode) View.VISIBLE else View.GONE
            hostAccRow.visibility =
                if (shizukuMode && curExclusiveService() == null) View.VISIBLE else View.GONE
        }

        // 「开启随选朗读」开关联动：改变它等于改变「本次是否走独占开启」
        // → 需重渲染说明文字（提示段跟着出现/消失）并刷新两个开关行的显隐。
        // 注意本监听在 isChecked 初值设好之后才挂，避免初始化时触发一次无意义回调。
        selectToSpeakSwitch.setOnCheckedChangeListener { _, checked ->
            selectToSpeakOn = checked
            renderMode(selected)
            updateSwitchRowVisibility(selected)
        }

        dialog.setOnShowListener {
            // 部分 ROM（MIUI/EMUI 等）定制主题下按钮文字为浅色，浅底上看不见，显式锁深色
            val btnColor = 0xFF212121.toInt()
            runCatching {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(btnColor)
            }
            updateNeutralVisibility(current)
            updateSwitchRowVisibility(current)
            dialog.listView?.setOnItemClickListener { _, _, position, _ ->
                selected = position
                renderMode(position)
                updateNeutralVisibility(position)
                updateSwitchRowVisibility(position)
                dialog.listView?.setItemChecked(position, true)
            }
        }
        dialog.show()
    }

    /**
     * UiAutomation 模式切换失败时弹引导对话框（无论失败原因，一律弹窗而非 toast）。
     * 内容显示真实失败原因；若原因涉及 Shizuku（未运行/未授权/残留注册），带「打开 Shizuku」按钮跳转。
     */
    private fun showUiAutomationFailDialog(activity: Activity, reason: String?) {
        val msg = reason ?: "未知错误"
        // 组件被禁用与「Shizuku 没开」是两种成因，按钮动作完全不同：
        // 前者点「打开 Shizuku」毫无作用，只能恢复组件 + 重启应用（组件恢复后 server 会重新投递 binder）。
        val componentDisabled = msg == REASON_SHIZUKU_COMPONENT_DISABLED
        val needShizuku = !componentDisabled && msg.contains("Shizuku", ignoreCase = true)
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Shizuku 模式 连接失败")
            .setMessage(
                if (componentDisabled)
                    "$msg\n\n已尝试自动恢复组件，请重新打开应用后再试（重启后 Shizuku 通道即可恢复）。"
                else msg
            )
            .setPositiveButton(
                when {
                    componentDisabled -> "重新打开应用"
                    needShizuku -> "打开 Shizuku"
                    else -> "确定"
                }
            ) { _, _ ->
                when {
                    componentDisabled -> ShizukuProviderGuard.restartProcessSoon(activity)
                    needShizuku && !AutomationShizuku.openShizuku(activity) ->
                        AliveUtils.toast(msg = "未找到 Shizuku 应用")
                }
            }
            .setNegativeButton("取消", null)
            .create()
        // 部分机型的强制深色（Force Dark）会把浅色弹窗局部反转，导致文字看不清，显式禁用
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialog.window?.decorView?.isForceDarkAllowed = false
            }
        }
        dialog.show()
        // 部分 ROM（MIUI/EMUI 等）定制主题下按钮文字为浅色，浅底上看不见，显式锁深色
        val btnColor = 0xFF212121.toInt()
        runCatching {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor)
        }
    }
    @JvmStatic
    fun showSXLDDialog(activity: Activity) {
        val msg = "随着ai的爆发，几乎人人都能写软件了，故wx加强了对自动化软件的检测，它能获取到设备上已开启自动化(主要是无障碍)的所有软件" +
                "但总不能一刀切的都不让其工作吧，于是分为了黑名单和白名单，其中 系统自带的 随选朗读 就在白名单中，" +
                "简单说，在wx中，黑名单中的软件无法正常工作，只有白名单中的软件才能正常工作，所以我们要开启系统自带的 随选朗读 并且 关闭其它所有的无障碍服务"
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("为何需要 随选朗读")
            .setMessage(msg)
            .setPositiveButton("确定") { _, _ ->
                NotificationUtilXpq.gotoAccessibilitySetting(activity)
            }
            .setNegativeButton("教程"){ _, _ ->
                viewUrl()
            }
            .create()
        // 部分机型的强制深色（Force Dark）会把浅色弹窗局部反转，导致文字看不清，显式禁用
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialog.window?.decorView?.isForceDarkAllowed = false
            }
        }
        dialog.show()
        // 部分 ROM（MIUI/EMUI 等）定制主题下按钮文字为浅色，浅底上看不见，显式锁深色
        val btnColor = 0xFF212121.toInt()
        runCatching {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor)
        }
    }
    @JvmStatic
    @JvmOverloads
    fun viewUrl(url: String = "https://mp.weixin.qq.com/s/DJ4_laTtiQKgYxByyreQ0Q", ctx: Context = appContext) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(url)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        ctx.startActivity(intent)
    }

    @JvmOverloads
    @JvmStatic
    fun setSMSMode(sms: Boolean = false) {
        MMKVUtil.put(MMKVConst.KEY_SENDSMS_MODE, sms)
    }
    @JvmOverloads
    @JvmStatic
    fun getSMSMode(default: Boolean = false): Boolean {
        return MMKVUtil.get(MMKVConst.KEY_SENDSMS_MODE,default)
    }

}
