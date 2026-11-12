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
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.Display
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.google.android.accessibility.ext.shell.XpqAdbShell
import com.google.android.accessibility.ext.shell.XpqSidecar
import com.google.android.accessibility.ext.utils.NotificationUtilXpq
import com.google.android.accessibility.ext.utils.safecheck.OverlayPermissionHelper
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
        h?.let {
            runCatching { it.stopUiAutomationLifecycle() }
            runCatching { it.onUiAutomationDestroy() }
        }
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

    /** 切到虚拟屏前的主通道 driver；为 null 表示当前不在虚拟屏模式。 */
    @Volatile
    private var displayBaseDriver: AccDriver? = null

    /**
     * 把节点读取/点击切到 [displayId] 这块虚拟屏。
     *
     * 窗口来源按当前通道自动选择：
     *  - 无障碍模式：真实服务的公开 `windowsOnAllDisplays`
     *  - UiAutomation 模式：`UiAutomation.getWindowsOnAllDisplays`（@hide）
     * 两通道都不可用时返回 false。之后 [rootInActiveWindow]、[windows]、[findFocus]
     * 都从这块屏取；返回键改走虚拟屏的 shell 注入。
     */
    @JvmStatic
    fun useDisplay(displayId: Int): Boolean {
        val current = driver
        if (current is DisplayScopedDriver) {
            current.displayId = displayId
            return true
        }
        // 窗口来源：优先真实无障碍服务（公开 API）；没有则走 UiAutomation 的隐藏多显示接口
        val service = SelectToSpeakServiceAbstract.instance
        val provider: (Int) -> List<AccessibilityWindowInfo> = when {
            service != null -> { id ->
                runCatching { service.windowsOnAllDisplays.get(id).orEmpty() }.getOrDefault(emptyList())
            }
            InvisibleAutomation.isConnected -> { id -> InvisibleAutomation.getWindowsOnDisplay(id) }
            else -> return false
        }
        displayBaseDriver = current
        driver = DisplayScopedDriver(current, displayId, provider)
        return true
    }

    /** 把节点读取/点击切回主屏（虚拟屏本身不释放）。 */
    @JvmStatic
    fun clearDisplay() {
        val base = displayBaseDriver ?: return
        displayBaseDriver = null
        driver = base
    }

    /** 当前节点读取所在的屏；不在虚拟屏模式时为 [Display.DEFAULT_DISPLAY]。 */
    @get:JvmStatic
    val currentDisplayId: Int
        get() = (driver as? DisplayScopedDriver)?.displayId ?: Display.DEFAULT_DISPLAY

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

    // ---- 悬浮窗权限（SYSTEM_ALERT_WINDOW，appops 特殊权限；自动开启只有 shell 一条通道） ----

    /** 当前是否已获得悬浮窗权限。纯进程内查询、零成本，随便调。 */
    @JvmStatic
    @JvmOverloads
    fun canDrawOverlays(context: Context = appContext): Boolean =
        OverlayPermissionHelper.canDrawOverlays(context)

    /**
     * 通过 shell 开启悬浮窗权限（`appops set <pkg> SYSTEM_ALERT_WINDOW allow`）—— **阻塞，勿主线程调用**。
     *
     * 已授权时直接短路（不 fork）；shell 不可用时 `success = false`，此时只能引导用户去
     * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 手动授权。详见 [OverlayPermissionHelper]。
     */
    @JvmStatic
    @JvmOverloads
    fun enableOverlayPermission(context: Context = appContext) =
        OverlayPermissionHelper.enableOverlayPermission(context)

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

    /**
     * **独占开启**指定无障碍服务：自动开启它，并把设备上其它所有无障碍服务**一并关闭**
     * （系统名单整体替换成只留这一个 + 总开关置 1）。
     *
     * 语义与 [enableAccessibilityService]`(exclusive = true)` 完全相同，独立命名只是让调用处读起来
     * 就是「只留这一个」。典型用途：只让宿主自己的无障碍服务在跑，避免设备上其它辅助/自动化工具
     * 与本库争抢事件与手势。
     *
     * ⚠ 副作用：名单整体替换后**原来正在运行的服务会被系统解绑**，包括宿主自己的另一个无障碍服务。
     * 若被关掉的恰好是本库依赖的那个实例，`SelectToSpeakServiceAbstract.instance` 会置空、通道需重连。
     * ⚠ 阻塞（binder + settings 命令 + waitFor），**勿在主线程调用**。
     *
     * @param flatComponent 服务 ComponentName 展平串，如 `"com.host/.MyAccService"`
     *                      或全限定 `"com.host/com.host.MyAccService"`。
     * @return [AccessibilityEnableHelper.EnableResult]；`message` 会额外写明「其它服务是否确实都已移除」
     *         （部分 ROM 会回填名单）。需要程序化判断时用 [enabledAccessibilityServices] 自行回读。
     */
    @JvmStatic
    @JvmOverloads
    fun enableAccessibilityServiceOnly(
        flatComponent: String = SELECTTOSPEAK,
        context: Context = appContext
    ): AccessibilityEnableHelper.EnableResult =
        AccessibilityEnableHelper.enableExclusiveAccessibilityService(context, flatComponent)

    /** 同上，按 Class 传（组件名 = `ComponentName(context.packageName, serviceClass.name)`）。 */
    @JvmStatic
    @JvmOverloads
    fun enableAccessibilityServiceOnly(
        serviceClass: Class<out AccessibilityService>,
        context: Context = appContext
    ): AccessibilityEnableHelper.EnableResult =
        enableAccessibilityServiceOnly(
            ComponentName(context.packageName, serviceClass.name).flattenToString(),
            context
        )

    /**
     * **独占开启宿主自己**的无障碍服务（组件名从宿主清单里声明了 `BIND_ACCESSIBILITY_SERVICE` 的服务反射得到），
     * 其它所有无障碍服务一并关闭。
     *
     * 宿主清单里没有这样的服务时返回失败（`success = false`），不会抛异常。
     */
    @JvmStatic
    @JvmOverloads
    fun enableHostAccessibilityServiceOnly(
        context: Context = appContext
    ): AccessibilityEnableHelper.EnableResult {
        val flat = hostAccessibilityServiceFlat() ?: return AccessibilityEnableHelper.EnableResult(
            false, false, "宿主清单未声明 BIND_ACCESSIBILITY_SERVICE 服务"
        )
        return enableAccessibilityServiceOnly(flat, context)
    }

    /**
     * 当前系统名单里的无障碍服务组件，按名单原顺序返回，**已归一化成统一的长式**
     * `包名/包名.类名`（`AccessibilityEnableHelper.canonicalComponent`）。
     * 因此可以跟 `AccIsOpenUtil.getEnabledServiceIds`（AMS 侧，同样归一化）直接比较。
     *
     * 优先走 shell（拿到的是真值）；shell 不可用时退回 ContentResolver。
     * ⚠ 这是「系统名单」，**不代表服务此刻正在运行** —— 名单与总开关缺一不可。
     */
    @JvmStatic
    @JvmOverloads
    fun enabledAccessibilityServices(context: Context = appContext): List<String> =
        AccessibilityEnableHelper.listEnabledAccessibilityServices(context)

    /**
     * 设备上已启用无障碍的**应用**列表（按应用名排序），**面向展示**用 —— 一个应用一项、带可读应用名，
     * 与 [enabledAccessibilityServices]（组件维度、返回 `包名/包名.类名` 串）互补。
     * 实现与边界说明见 [AccessibilityEnableHelper.enabledAccessibilityApps]。
     *
     * @param onlyActive true = 只列真正生效的（也不读名单、不走 shell）；默认 false 会连
     *                   「名单里有、实际没跑」的条目一起返回，由 [AccessibilityEnableHelper.EnabledAccessibilityApp.isActive] 区分。
     */
    @JvmStatic
    @JvmOverloads
    fun enabledAccessibilityApps(
        context: Context = appContext,
        onlyActive: Boolean = false
    ): List<AccessibilityEnableHelper.EnabledAccessibilityApp> =
        AccessibilityEnableHelper.enabledAccessibilityApps(context, onlyActive)

    /** 切换通道；切换时先主动关闭旧通道，再切换到新通道并同步 accessibilityService 全局变量指向。 */
    @JvmStatic
    fun use(mode: EngineMode) {
        // 切通道时先退回主屏 driver，避免把虚拟屏包装带进新通道
        if (driver is DisplayScopedDriver) clearDisplay()
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
     * 连接成功后还会**自动开启悬浮窗权限**（`appops set <pkg> SYSTEM_ALERT_WINDOW allow`，走 shell 通道）：
     * UiAutomation 通道下没有真实无障碍实例，悬浮窗只能走 `TYPE_APPLICATION_OVERLAY`，缺这条权限加不上。
     * 已授权时短路（不 fork）；shell 不可用或厂商拦截时失败，**只记日志、不改变连接成功结果**。
     * 见 [OverlayPermissionHelper]。
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
                // 悬浮窗权限必须**最先**处理：UiAutomation 通道下没有真实无障碍实例，悬浮窗只能用
                // TYPE_APPLICATION_OVERLAY(2038)，缺 SYSTEM_ALERT_WINDOW 会直接 addView 失败。
                // 放在 autoInit() 之前是**必需的**：autoInit 内部是 handler.post 切主线程（这里异步返回），
                // 只有在当前线程同步跑完 appops，才能保证它真正落地时权限已就绪。
                // 失败只记日志、不改变连接结果（部分 ROM 另有权限管理，不能因此判定连接失败）。
                runCatching {
                    val overlay = OverlayPermissionHelper.enableOverlayPermission(appContext)
                    if (overlay.success) log("✅ 悬浮窗权限: ${overlay.message}")
                    else log("✗ 自动开启悬浮窗权限失败: ${overlay.message}")
                }.onFailure { log("✗ 自动开启悬浮窗权限异常: ${it.message}") }
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
            val builtinMode = XpqAdbShell.isPreferred(appContext)
            // 内置 ADB 模式：必须**先**把 Sidecar 拉起来，再 disconnect()。
            // 反注册（unregisterUiTestAutomationService）同样要经 shell 身份的 binder 发出，
            // Sidecar 没起来时这一步会静默失败 → 旧注册残留 → 紧接着 register 报 already registered。
            if (builtinMode) {
                log("内置 ADB 模式：启动 shell 侧 Sidecar…")
                if (!XpqSidecar.ensureStarted(appContext)) {
                    val reason = XpqSidecar.lastError ?: "WiFi 模式通道不可用"
                    log("✗ $reason")
                    fail(reason)
                    return@Thread
                }
                log("Sidecar 已就绪（uid=${XpqSidecar.sidecarUid}）")
            }
            // 连接前先清理旧连接，避免 system_server 残留注册导致 "already registered" 失败
            runCatching { disconnect() }
            if (builtinMode) {
                finishConnect(connect(log))
                return@Thread
            }
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
        // 本方法每次调用都会反射 newInstance 出一个**新的**宿主服务实例。若直接覆盖
        // [bridgedHandler]，上一个实例就再也没有任何引用能触达 —— 它注册的接收器、挂上去的
        // 悬浮窗全部变成无法回收的「野资源」。
        //
        // 后果不只是泄漏：UiAutomation 通道下悬浮窗只能用 TYPE_APPLICATION_OVERLAY(2038)，
        // 它属于**非 trusted overlay**，Android 12+ 会按「遮挡不透明度」丢弃其下方的触摸
        // （InputDispatcher: Dropping untrusted touch，阈值默认 0.8）。两套全屏悬浮窗叠加即
        // 1-(1-0.8)^2 = 0.96 > 0.8 ⇒ **整机触摸全部失效**，而且每次重连都会再叠一套。
        // 故替换前必须让旧实例先走一遍退役流程，由它自己把窗口与监听收干净。
        val previous = bridgedHandler
        if (previous != null && previous !== handler) {
            runCatching { previous.stopUiAutomationLifecycle() }
            runCatching { previous.onUiAutomationDestroy() }
        }
        bridgedHandler = handler
        // 手动 new 的实例从未被系统 attach，ContextWrapper.mBase 为空 → 事件链路里任何一次
        // Context 调用（getString / getSystemService / registerReceiver / packageManager ...）都会 NPE，
        // 且常被上层 catch(Throwable){} 吞掉，外在表现是「什么都没发生」。桥接前统一补齐，让所有
        // 接入本库的宿主一同免疫（含下面 onUiAutomationReady 里宿主自己用到的 Context）。
        runCatching { handler.ensureAppContext() }
        // 生命周期也要一起补齐：手动 new 的实例收不到 onCreate/onServiceConnected，LifecycleRegistry
        // 会永远停在 INITIALIZED，而 ComposeView 的重组循环**只在 ON_CREATE 时启动**
        // ⇒ LifecycleOwner = 本实例的 Compose 悬浮窗（如手势库的手势条）会「窗口挂上了、
        //   内容却永不合成」：点不动、滑也不动，且不报任何错。必须在挂悬浮窗（onUiAutomationReady）
        //   之前补上，让它与真实无障碍模式的生命周期状态一致。
        runCatching { handler.startUiAutomationLifecycle() }
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
     *                            非空时（如 [SELECTTOSPEAK]）取值完全以本参数为准 —— 弹窗内那个开关
     *                            退化为**只读展示**（见下文），不再影响本次配置。
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
     * （独占路径下后者的持久化判断本就不生效）。
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
        val items = arrayOf("无障碍模式", "WiFi 模式（Shizuku）", "WiFi 模式")
        val current = loadEngineMode().ordinal

        // 第三项 = UiAutomation 引擎 + shell 走内置 ADB（与 Shizuku 模式同一套引擎，只是通道不同）
        val builtinPreferred = XpqAdbShell.isPreferred(activity)
        val currentIndex = if (builtinPreferred) 2 else current

        // 选项下标 -> 实际引擎模式：只有第一项是无障碍，第二/第三项都是 UiAutomation
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

        // 每个模式对应的说明文字，切换选项时动态更新到说明区。
        // Shizuku 模式的下载地址 / 使用教程 / 启动授权步骤都收进了「打开 Shizuku 设置界面」行的
        // 行内步骤清单里，这里只留一句话，避免说明区过长。
        // 说明里**不再提示**「关闭其它无障碍 / 保留随选朗读」—— 那件事由点「确定」时的代码独占完成，
        // 用户无需照着做；相关说明见开关行右侧的「？」帮助按钮（showSXLDDialog）。
        fun describeMode(mode: EngineMode): String = when {
            mode == EngineMode.ACCESSIBILITY_SERVICE && !hasAccessibility ->
                "当前版本不支持无障碍模式,如果确定需要无障碍的版本，可下载支持无障碍的版本使用"
            mode == EngineMode.ACCESSIBILITY_SERVICE ->
                        "无需额外安装软件,但是部分软件(比如：银行类软件,wx)检测设备上无障碍服务开启时，可能出现安全提示"
            AutomationShizuku.isManagerMergedIntoHost() ->
                        "为什么引入该模式：" +
                        "\n一句话：降低被检测到的概率" +
                        "\n本软件已内置 Shizuku 管理器，无需额外下载"
            else ->
                        "为什么引入该模式：" +
                        "\n一句话：降低被检测到的概率" +
                        "\n需要额外下载一个免费开源的 Shizuku 软件"
        }

        val density = activity.resources.displayMetrics.density
        // 标题（当前模式名）保持在顶部并居中
        val headerView = TextView(activity).apply {
            text = items[currentIndex]
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(0xFF212121.toInt())
            setPadding(
                (20 * density).toInt(),
                (18 * density).toInt(),
                (20 * density).toInt(),
                (4 * density).toInt()
            )
        }
        // 说明文字放在选项列表和开关的下方（底部），不再占标题区
        val titleView = TextView(activity).apply {
            textSize = 14f
            // 显式锁定文字颜色：弹窗背景锁定为浅色（见下方 Builder 主题参数），深浅模式下都可读。
            // 部分机型暗黑模式下默认色会解析成浅色压浅底，导致文字看不清。
            setTextColor(0xFF212121.toInt())
            setPadding(
                (20 * density).toInt(),
                (10 * density).toInt(),
                (20 * density).toInt(),
                (10 * density).toInt()
            )
        }

        // 渲染指定模式的说明文字：
        // 无障碍模式 —— 纯文字说明，**不放可点击项**（原因见下面的分支注释）
        // Shizuku 模式 —— 纯文字说明；下载地址 / 教程 / 启动授权步骤都在「开启Shizuku模式」弹窗里
        // 内置 ADB 模式 —— 纯文字说明 + 配对面板；引擎与 Shizuku 模式相同（UiAutomation），只是 shell 来源不同
        // 面板引用：无障碍模式隐藏两个 Shizuku 开关；第三项额外显示配对面板。
        // renderMode 是局部函数、不能前向引用，这里先占位，等面板创建后再赋值。
        var switchPanelRef: View? = null
        var builtinPanelRef: View? = null
        var shizukuPanelRef: View? = null
        // 顶部说明保持简短（避免弹窗过高），完整步骤在「打开WiFi设置界面」行里就地展开
        val builtinDesc = "为什么引入该模式：" +
                "\n一句话：降低被检测到的概率" +
                "\n与 WiFi 模式（Shizuku）效果一样"
        // 行内步骤区的操作按钮要跳系统页 / 弹授权框，先把模式弹窗关掉；dialog 在下方创建，这里先占位
        var modeDialogRef: AlertDialog? = null
        fun renderMode(index: Int) {
            val builtin = index == 2
            // 顶部标题始终显示当前选中项
            headerView.text = items[index]
            // 第三项与 Shizuku 模式共用同一套开关（引擎相同），额外多一个配对面板
            switchPanelRef?.visibility = if (index == 0) View.GONE else View.VISIBLE
            builtinPanelRef?.visibility = if (builtin) View.VISIBLE else View.GONE
            shizukuPanelRef?.visibility = if (index == 1) View.VISIBLE else View.GONE
            if (builtin) {
                // 步骤入口是「打开WiFi设置界面」行（见下方 guideRow），说明文字保持纯文本
                titleView.setOnClickListener(null)
                titleView.movementMethod = null
                titleView.text = builtinDesc
                return
            }
            titleView.setOnClickListener(null)
            val mode = indexToMode(index)
            if (mode == EngineMode.ACCESSIBILITY_SERVICE) {
                // 无障碍模式**不再放可点击链接**：原来那行「请跳转到☞☞：https://settings.设置」点开的是
                // showCheckDialog 引导，而点「确定」在失败 / 无权限时弹的是**同一个**引导对话框
                // （见 applyModeAndGuideToSetting，调用参数一字不差），成功时就更不需要引导 ——
                // 属于重复入口；且它自己不切模式、只弹引导（"半截操作"），容易让人以为点完就切好了。
                titleView.movementMethod = null
                titleView.text = describeMode(mode)
            } else {
                // Shizuku 模式：说明保持纯文本，下载地址 / 使用教程 / 启动授权步骤
                // 全部收进「打开 Shizuku 设置界面」行的行内步骤清单。
                // 「随选朗读」说明仍由开关行右侧的「？」帮助按钮（showSXLDDialog）提供。
                titleView.movementMethod = null
                titleView.text = describeMode(mode)
            }
        }
        renderMode(currentIndex)

        // 记录当前选中项；点击「确定」时才真正切换并持久化。
        // 提前声明：下面「开启随选朗读」开关的监听里要重渲染说明文字，需要读它。
        var selected = currentIndex

        // 「开启随选朗读」开关：等价于调用方把 enableServiceString 传成 [SELECTTOSPEAK]——
        // 开启后切到 Shizuku 模式时会「独占」开启系统的随选朗读（关掉设备上其它无障碍服务）。
        // 调用方显式传了 enableServiceString 时本行仍显示，但转为只读：勾选状态由传入值决定，
        // 只有开关自身置灰（文字颜色不变），表示本次配置由调用方决定、用户改不了。
        val selectToSpeakSwitch = SwitchCompat(activity).apply {
            isChecked = selectToSpeakOn
            isEnabled = !selectToSpeakLocked
        }
        // 「开 / 关」直接画在开关圆钮内部（跟随圆钮滑动），不再单独占一行文字
        fun attachThumbLabel(sw: SwitchCompat): ThumbTextDrawable =
            ThumbTextDrawable(sw.thumbDrawable).also { sw.thumbDrawable = it }
        fun updateThumbLabel(d: ThumbTextDrawable, checked: Boolean) {
            d.label = if (checked) "开" else "关"
            // 固定深色 + 白色描边，开 / 关两种圆钮底色下都能看清
            d.labelColor = 0xFF37474F.toInt()
            d.labelSize = 10f * activity.resources.displayMetrics.scaledDensity
        }
        val selectToSpeakThumb = attachThumbLabel(selectToSpeakSwitch)
        updateThumbLabel(selectToSpeakThumb, selectToSpeakOn)
        val selectToSpeakTitleView = TextView(activity).apply {
            text = "同时开启 随选朗读"
            textSize = 14f
            // 与标题区同款：显式锁深色，避免暗黑模式下浅色压浅底。
            // 只读与否不改变文字颜色——禁用状态完全由右侧开关自身置灰表达。
            setTextColor(0xFF212121.toInt())
        }
        // 两个开关行的「？」帮助按钮共用同一套外观与构造：
        // 18dp 圆环 + 蓝色「?」紧贴标题右侧，点击弹各自的说明对话框。
        // 常驻可点、不受开关只读/禁用状态影响——它讲的是「为什么要开」，与本次能否改无关。
        val helpDotSize = (18 * density).toInt()
        fun buildHelpDot(desc: String, onClick: () -> Unit): TextView = TextView(activity).apply {
            text = "?"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A73E8.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke((1 * density).toInt().coerceAtLeast(1), 0xFF1A73E8.toInt())
            }
            isClickable = true
            isFocusable = true
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        fun buildTitleRow(title: TextView, dot: TextView, onClick: () -> Unit): LinearLayout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 整行（标题文字 + ？）都可点：？本身只有 18dp，单点太难点中
                addView(title)
                addView(
                    dot,
                    LinearLayout.LayoutParams(helpDotSize, helpDotSize).apply {
                        marginStart = (6 * density).toInt()
                    }
                )
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                isClickable = true
                isFocusable = true
                // 点击反馈：借用主题水波纹，点了有回应
                val outValue = TypedValue()
                if (activity.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, outValue, true
                    ) && outValue.resourceId != 0
                ) {
                    setBackgroundResource(outValue.resourceId)
                }
                setOnClickListener { onClick() }
            }
        val showSelectToSpeakHelp: () -> Unit = { showSXLDDialog(activity) }
        val selectToSpeakTitleRow = buildTitleRow(
            selectToSpeakTitleView,
            buildHelpDot("为何需要 随选朗读", showSelectToSpeakHelp),
            showSelectToSpeakHelp
        )
        val selectToSpeakDescView = TextView(activity).apply {
            // 文案与「本次是不是只读」无关：无论配置由谁决定，都展示同一句说明、保持同一颜色。
            text = "开启后，切换到 WiFi 模式时会一并开启系统的「随选朗读」无障碍服务和关闭 其它所有无障碍(主要使用场景在WX内时，开启本开关)"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
        }
        val selectToSpeakLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(selectToSpeakTitleRow)
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
        val hostAccThumb = attachThumbLabel(hostAccSwitch)
        updateThumbLabel(hostAccThumb, hostAccSwitch.isChecked)
        val hostAccTitleView = TextView(activity).apply {
            text = "同时开启 本应用无障碍"
            textSize = 14f
            // 与标题区同款：显式锁深色，避免暗黑模式下浅色压浅底
            setTextColor(0xFF212121.toInt())
        }
        val hostAccDescView = TextView(activity).apply {
            text = "开启后，切换到 WiFi 模式时会一并打开本应用的无障碍服务(主要使用场景不在WX内时，开启本开关)"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
        }
        val showHostAccHelp: () -> Unit = { showHostAccDialog(activity, imgRes) }
        val hostAccTitleRow = buildTitleRow(
            hostAccTitleView,
            buildHelpDot("为何需要 本应用无障碍", showHostAccHelp),
            showHostAccHelp
        )
        val hostAccLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hostAccTitleRow)
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

        // 两个开关行合并成一个竖直线性布局，作为自定义面板的一部分
        // （setView 只能挂一个 View；面板与单选列表共存，custom 面板在列表上方）
        val switchPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(selectToSpeakRow)
            addView(hostAccRow)
        }

        // 内置 ADB 面板：连接全自动（启动/重启都会自己重连），这里只保留「首次配对」入口和状态。
        fun adbRow(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun adbButton(label: String): Button = Button(activity).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(0xFF212121.toInt())
            minimumHeight = 0
            minHeight = (48 * density).toInt()
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        // 配对入口只保留在指南里（第 4 步的「开始配对」）；「启动」由「确定」覆盖（切模式时自动连接），
        // 所以弹窗里不再单独放「配对 / 启动」按钮和调试状态行。
        fun openSystemSettings(vararg actions: String) {
            for (action in actions) {
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { activity.startActivity(intent) }.isSuccess) return
            }
            AliveUtils.toast(msg = "未能打开系统设置")
        }
        // 直达「无线调试」页（对齐 Shizuku 的 SettingsPage.Developer.WirelessDebugging）：
        // 用 QS 磁贴的偏好页打开「无线调试」，失败再回退到开发者选项并高亮该条目。
        fun openWirelessDebuggingSettings() {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            fun tryIntent(intent: Intent): Boolean =
                runCatching { activity.startActivity(intent) }.isSuccess
            fun highlightInDevOptions(): Boolean = tryIntent(
                Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    // 开发者选项页内高亮「无线调试」开关（部分 ROM 没有独立入口）
                    putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                    addFlags(flags)
                }
            )
            val adbEnabled = runCatching {
                android.provider.Settings.Global.getInt(
                    activity.contentResolver,
                    android.provider.Settings.Global.ADB_ENABLED, 0
                )
            }.getOrDefault(0)
            if (adbEnabled > 0) {
                val direct = Intent(android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES).apply {
                    setPackage("com.android.settings")
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(
                            "com.android.settings",
                            "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging"
                        )
                    )
                    addFlags(flags)
                }
                if (tryIntent(direct)) return
            }
            if (highlightInDevOptions()) return
            openSystemSettings(
                android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                android.provider.Settings.ACTION_SETTINGS
            )
        }
        // 开发者选项是否已开启（关闭状态下进 DevelopmentSettings 会进不去，只能先去「关于手机」连点版本号）
        val devOptionsEnabled = runCatching {
            android.provider.Settings.Global.getInt(
                activity.contentResolver,
                android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
        }.getOrDefault(false)
        // 配对：挂浮窗 + 跳设置（系统配对弹窗一离开设置就结束配对服务，必须先挂好浮窗）
        fun startPairingFlow() {
            if (XpqAdbShell.isConnected()) {
                AliveUtils.toast(msg = "已配对并连接，不用重复配对")
                return
            }
            if (!android.provider.Settings.canDrawOverlays(activity)) {
                AliveUtils.toast(msg = "请先允许「显示在其他应用上层」，再回来点一次")
                runCatching {
                    activity.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return
            }
            if (!com.google.android.accessibility.ext.shell.AdbPairOverlay.show(activity)) {
                AliveUtils.toast(msg = "配对码浮窗打开失败，请检查悬浮窗权限")
                return
            }
            if (devOptionsEnabled) {
                openWirelessDebuggingSettings()
            } else {
                AliveUtils.toast(msg = "请先在「关于手机」里连点版本号 7 次开启开发者选项，再回来点一次")
                openSystemSettings(
                    android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS,
                    android.provider.Settings.ACTION_SETTINGS
                )
            }
        }

        // 高级设置：对齐 Shizuku 的设置页（自启动 / Watchdog / TCP 模式 / TCP 端口）
        val adbAdvancedRow = adbRow().apply {
            addView(
                adbButton("高级设置"),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // 高级设置入口暂时隐藏（不给用户增加配置负担）；代码保留，需要恢复时删掉下面这行
        adbAdvancedRow.visibility = View.GONE
        (adbAdvancedRow.getChildAt(0) as Button).setOnClickListener {
            val pad = (12 * density).toInt()
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (20 * density).toInt(), (4 * density).toInt(),
                    (20 * density).toInt(), (4 * density).toInt()
                )
            }
            fun titleText(t: String) = TextView(activity).apply {
                text = t
                textSize = 15f
                setTextColor(0xFF212121.toInt())
            }
            fun descText(t: String) = TextView(activity).apply {
                text = t
                textSize = 12f
                setTextColor(0xFF757575.toInt())
            }
            fun switchRow(
                title: String,
                desc: String,
                checked: Boolean,
                onChange: (Boolean) -> Unit,
            ): LinearLayout {
                val sw = SwitchCompat(activity).apply { isChecked = checked }
                val texts = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleText(title))
                    addView(descText(desc))
                }
                sw.setOnCheckedChangeListener { _, c -> onChange(c) }
                return LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, pad, 0, pad)
                    addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(sw)
                }
            }
            // 开启自启动 / 看门狗时，引导忽略电池优化（否则厂商会杀掉后台，开机起不来 / 看门狗也被杀）
            fun maybeRequestIgnoreBatteryOptimizations() {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
                val guide = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle("允许后台运行")
                    .setMessage(
                        "开启自启动 / 看门狗后，系统可能因为电池优化把后台进程杀掉，导致开机不自动恢复、" +
                                "看门狗也不工作。\n\n建议允许本应用忽略电池优化。"
                    )
                    .setPositiveButton("去设置") { _, _ ->
                        runCatching {
                            activity.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${activity.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    .setNegativeButton("以后再说", null)
                    .create()
                guide.setOnShowListener {
                    runCatching {
                        val btnColor = 0xFF212121.toInt()
                        guide.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor)
                        guide.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor)
                    }
                }
                guide.show()
            }
            panel.addView(
                switchRow(
                    "自启动",
                    "开机后自动恢复 WiFi 模式通道",
                    XpqAdbShell.isAutoStartEnabled(activity)
                ) { on ->
                    XpqAdbShell.setAutoStartEnabled(activity, on)
                    AliveUtils.toast(msg = if (on) "开机后会自动恢复 WiFi 模式" else "已关闭开机自启动")
                    if (on) maybeRequestIgnoreBatteryOptimizations()
                }
            )
            panel.addView(
                switchRow(
                    "Watchdog（看门狗）",
                    "如果 WiFi 模式通道意外停止（崩溃），则自动重启",
                    XpqAdbShell.isWatchdogEnabled(activity)
                ) { on ->
                    XpqAdbShell.setWatchdogEnabled(activity, on)
                    AliveUtils.toast(
                        msg = if (on) "看门狗已开启（前台服务常驻，每 30 秒检查一次）" else "看门狗已关闭"
                    )
                    if (on) maybeRequestIgnoreBatteryOptimizations()
                }
            )
            panel.addView(
                switchRow(
                    "TCP 模式",
                    "允许在没有 Wi-Fi 的情况下重连（设备重启后需先连一次无线调试）",
                    XpqAdbShell.isTcpMode(activity)
                ) { on ->
                    XpqAdbShell.setTcpMode(activity, on)
                    if (!on) {
                        AliveUtils.toast(msg = "已关闭 TCP 模式，重连会回到无线调试端口")
                    } else if (!XpqAdbShell.isConnected()) {
                        AliveUtils.toast(msg = "已开启：下次连接时自动切到 TCP 端口")
                    } else {
                        AliveUtils.toast(msg = "正在切换到 TCP 模式…")
                        Thread {
                            val ok = runCatching { XpqAdbShell.applyTcpMode(activity) }.getOrDefault(false)
                            activity.runOnUiThread {
                                AliveUtils.toast(
                                    msg = if (ok) "已切换到 TCP 模式（端口 ${XpqAdbShell.tcpPort(activity)}）"
                                    else "切换失败：${XpqAdbShell.lastError ?: "未知原因"}"
                                )
                            }
                        }.start()
                    }
                }
            )
            val tcpPortNow = XpqAdbShell.tcpPort(activity)
            val portRow = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad)
                isClickable = true
                isFocusable = true
                addView(titleText("TCP 端口"))
                addView(
                    descText(
                        if (tcpPortNow == XpqAdbShell.defaultTcpPort())
                            "默认值（$tcpPortNow），取值范围 1-65535"
                        else "当前：$tcpPortNow（默认 ${XpqAdbShell.defaultTcpPort()}），取值范围 1-65535"
                    )
                )
                setOnClickListener {
                    val input = EditText(activity).apply {
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        hint = "端口范围 1-65535，留空使用默认 ${XpqAdbShell.defaultTcpPort()}"
                        setSingleLine(true)
                        if (tcpPortNow != XpqAdbShell.defaultTcpPort()) setText(tcpPortNow.toString())
                    }
                    val portDialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setTitle("TCP 端口")
                        .setView(input)
                        .setPositiveButton("保存") { _, _ ->
                            val p = input.text.toString().trim().toIntOrNull()
                                ?: XpqAdbShell.defaultTcpPort()
                            if (p !in 1..65535) {
                                AliveUtils.toast(msg = "端口需在 1-65535 之间")
                                return@setPositiveButton
                            }
                            XpqAdbShell.setTcpPort(activity, p)
                            AliveUtils.toast(msg = "TCP 端口已设为 $p")
                            if (XpqAdbShell.isTcpMode(activity) && XpqAdbShell.isConnected()) {
                                Thread { runCatching { XpqAdbShell.applyTcpMode(activity) } }.start()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .create()
                    portDialog.setOnShowListener {
                        runCatching {
                            val btnColor = 0xFF212121.toInt()
                            portDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor)
                            portDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor)
                        }
                    }
                    portDialog.show()
                }
            }
            panel.addView(portRow)
            val advancedDialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("WiFi 模式高级设置")
                .setView(panel)
                .setPositiveButton("关闭", null)
                .create()
            // 部分 ROM 主题下按钮文字是浅色，浅底上看不清，显式锁深色
            advancedDialog.setOnShowListener {
                runCatching {
                    val btnColor = 0xFF212121.toInt()
                    advancedDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor)
                    advancedDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor)
                    advancedDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(btnColor)
                }
            }
            advancedDialog.show()
        }

        // 两个模式入口行右侧的状态标记：步骤全部完成 → ✓，否则 → ×。
        // 外观与行内步骤前的圆标一致：完成 = 绿底白字 ✓，未完成 = 红底白字 ×，尺寸同为 28dp。
        fun buildStatusBadge(): TextView = TextView(activity).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (28 * density).toInt(), (28 * density).toInt()
            )
        }
        fun renderStatus(badge: TextView, ready: Boolean) {
            badge.text = if (ready) "✓" else "×"
            badge.setTextColor(0xFFFFFFFF.toInt())
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (ready) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            }
            badge.contentDescription = if (ready) "已完成" else "未完成"
        }

        // ---- 行内步骤：点「打开WiFi设置界面 / 打开 Shizuku 设置界面」行，就地展开步骤清单 ----
        // 字段与原来的指南弹窗一致：每步再点开可看说明、下载/教程链接和操作按钮。
        class InlineStep(
            val title: String,
            val brief: String,
            val body: String,
            val links: List<Pair<String, String>> = emptyList(),
            val actionLabel: String? = null,
            val action: (() -> Unit)? = null,
            val extraLink: Pair<String, () -> Unit>? = null,
            val detect: () -> Boolean,
        )

        fun openUrl(url: String) {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        // 软件内加载网页（B 站教程），不跳外部浏览器
        fun showMoreOpenTip() {
            val web = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    // 站内跳转继续在弹窗里打开
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = false
                }
                setBackgroundColor(0xFFFFFFFF.toInt())
                loadUrl("https://mp.weixin.qq.com/s/woViYTifRtvTDLAV9ss1mg")
            }
            val tip = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("不同品牌机型额外开启项")
                .setView(web)
                .setPositiveButton("关闭", null)
                .create()
            tip.setOnShowListener {
                runCatching {
                    tip.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF212121.toInt())
                    // 网页需要足够的可视面积：占屏宽 92%、高 70%
                    tip.window?.setLayout(
                        (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                        (activity.resources.displayMetrics.heightPixels * 0.7f).toInt()
                    )
                }
            }
            // 关闭弹窗时释放 WebView，避免视频/音频继续在后台跑
            tip.setOnDismissListener {
                runCatching {
                    web.stopLoading()
                    web.destroy()
                }
            }
            tip.show()
        }

        // 「找不到无线调试？」兜底说明（随步骤一起展示，不再单独弹指南窗）
        fun showNotFoundTip() {
            val tip = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("找不到「无线调试」？")
                .setMessage(
                    "先在开发者选项里往下翻，找「无线调试」。\n\n" +
                            "如果完全没有这一项：\n" +
                            "· 确认开发者选项已经打开\n" +
                            "· 部分机型叫「无线调试（开发者选项）」，或需要先连上 Wi-Fi\n" +
                            "· Android 11 以下没有无线调试，只能用电脑连一次"
                )
                .setPositiveButton("打开开发者选项") { _, _ ->
                    openSystemSettings(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                        android.provider.Settings.ACTION_SETTINGS
                    )
                }
                .setNegativeButton("知道了", null)
                .create()
            tip.setOnShowListener {
                runCatching {
                    val color = 0xFF212121.toInt()
                    tip.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(color)
                    tip.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(color)
                }
            }
            tip.show()
        }

        /**
         * 构建可展开的步骤清单卡片。
         * @return 视图 + 刷新函数（只在检测结果变化时重建，展开状态 / 手动完成标记保留）
         */
        fun buildInlineSteps(steps: List<InlineStep>): Pair<View, () -> Unit> {
            val expandedSteps = mutableSetOf<Int>()
            val manualDone = mutableSetOf<Int>()
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            }
            fun isDone(i: Int) = manualDone.contains(i) || steps[i].detect()
            fun signature() = steps.indices.joinToString("") { if (isDone(it)) "1" else "0" }
            fun currentStep(): Int? = steps.indices.firstOrNull { !isDone(it) }

            fun badge(i: Int): TextView = TextView(activity).apply {
                gravity = Gravity.CENTER
                val done = isDone(i)
                text = if (done) "✓" else "${i + 1}"
                textSize = if (done) 13f else 12f
                setTextColor(if (done) 0xFFFFFFFF.toInt() else 0xFF616161.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (done) 0xFF2E7D32.toInt() else 0xFFEDEDED.toInt())
                }
            }
            fun smallText(t: String, color: Int): TextView = TextView(activity).apply {
                text = t
                textSize = 11.5f
                setTextColor(color)
                setLineSpacing(0f, 1.15f)
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            fun actionPill(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(
                    (12 * density).toInt(), (5 * density).toInt(),
                    (12 * density).toInt(), (5 * density).toInt()
                )
                background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(0xFF1565C0.toInt())
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            fun linkText(label: String, onClick: () -> Unit): TextView =
                smallText(label, 0xFF1A73E8.toInt()).apply {
                    textSize = 12f
                    setPadding(0, (4 * density).toInt(), 0, 0)
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    val outValue = TypedValue()
                    if (activity.theme.resolveAttribute(
                            android.R.attr.selectableItemBackground, outValue, true
                        ) && outValue.resourceId != 0
                    ) {
                        setBackgroundResource(outValue.resourceId)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }

            lateinit var render: () -> Unit
            fun buildRow(i: Int): View {
                val s = steps[i]
                val open = expandedSteps.contains(i)
                val head = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (open) expandedSteps.remove(i) else expandedSteps.add(i)
                        render()
                    }
                }
                head.addView(
                    badge(i),
                    LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
                )
                val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(TextView(activity).apply {
                    text = s.title
                    textSize = 13.5f
                    setTextColor(if (open || isDone(i)) 0xFF212121.toInt() else 0xFF8A8A8A.toInt())
                })
                if (open) {
                    labels.addView(smallText(s.body, 0xFF757575.toInt()))
                    s.extraLink?.let { (label, onClick) -> labels.addView(linkText(label, onClick)) }
                    s.links.forEach { (label, url) ->
                        labels.addView(linkText(label) { openUrl(url) })
                    }
                    s.actionLabel?.let { label ->
                        labels.addView(
                            actionPill(label) {
                                // 跳系统页 / 弹授权框前先收起模式弹窗，免得挡住
                                modeDialogRef?.dismiss()
                                s.action?.invoke()
                            },
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (8 * density).toInt() }
                        )
                    }
                    if (!isDone(i) && currentStep() == i) {
                        labels.addView(linkText("我已经完成了，继续") {
                            manualDone += i
                            expandedSteps.remove(i)
                            currentStep()?.let { expandedSteps.add(it) }
                            render()
                        })
                    }
                } else {
                    labels.addView(smallText(s.brief, 0xFF9E9E9E.toInt()))
                }
                head.addView(
                    labels,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (8 * density).toInt()
                    }
                )
                return head
            }

            render = {
                card.removeAllViews()
                // 步骤总数提示（在第一步上面）
                card.addView(
                    smallText("共 ${steps.size} 步", 0xFF8A8A8A.toInt()).apply {
                        setPadding(0, 0, 0, (2 * density).toInt())
                    }
                )
                steps.indices.forEach { i ->
                    if (i > 0) {
                        card.addView(
                            View(activity).apply { setBackgroundColor(0xFFEFEFEF.toInt()) },
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 1
                            ).apply { marginStart = (30 * density).toInt() }
                        )
                    }
                    card.addView(
                        buildRow(i),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            }
            // 默认展开「当前该做的那一步」（与原来的指南一致）
            currentStep()?.let { expandedSteps.add(it) }
            render()

            // 步骤区自身不再单独滚动：外层的模式面板已整体限高 + 可滚动，
            // 两层 ScrollView 嵌套会让滑动手感变差。
            var lastSignature = signature()
            return card to {
                val next = signature()
                if (next != lastSignature) {
                    lastSignature = next
                    render()
                }
            }
        }

        // WiFi 模式步骤：检测项与状态标记口径一致（开发者选项 / USB 调试 / 无线调试 / 已连接）
        fun globalOn(key: String) = runCatching {
            Settings.Global.getInt(activity.contentResolver, key, 0) == 1
        }.getOrDefault(false)
        val wifiSteps = listOf(
            InlineStep(
                title = "打开开发者选项",
                brief = "在「关于手机」里连点「版本号」7 次",
                body = "在「关于手机」里连续点「版本号」7 次，就能打开开发者选项。",
                actionLabel = "去「关于手机」里开启开发者选项",
                action = {
                    openSystemSettings(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                        android.provider.Settings.ACTION_SETTINGS
                    )
                },
                detect = { globalOn(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) },
            ),
            InlineStep(
                title = "打开 USB 调试",
                brief = "断开 Wi-Fi 时也能继续操作",
                body = "开发者选项里打开「USB 调试」。这样断开 Wi-Fi 时也能继续操作。",
                actionLabel = "去打开「USB 调试」",
                action = {
                    openSystemSettings(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                        android.provider.Settings.ACTION_SETTINGS
                    )
                },
                extraLink = "不同品牌机型额外开启项" to { showMoreOpenTip() },
                detect = { globalOn(Settings.Global.ADB_ENABLED) },
            ),
            InlineStep(
                title = "打开无线调试",
                brief = "在开发者选项里打开「无线调试」",
                body = "开发者选项里找到「无线调试」并打开。问你是否允许时选「允许」，勾上" +
                        "「始终允许使用此网络」以后就不用再点。需要连着 Wi-Fi（不用能上网，连上就行）。",
                actionLabel = "打开无线调试",
                action = { openWirelessDebuggingSettings() },
                extraLink = "找不到无线调试？" to { showNotFoundTip() },
                // 原版开发者选项写的是 adb_wifi_enabled（不是 adb_wireless_enabled）
                detect = { globalOn("adb_wifi_enabled") },
            ),
            InlineStep(
                title = "用配对码配对",
                brief = "填 6 位配对码，只要配一次",
                body = "点「开始配对」，会挂出配对码浮窗并跳到「无线调试」。在里面点" +
                        "「使用配对码配对设备」，把 6 位码填进浮窗点「配对」。配对弹窗一关闭，" +
                        "配对服务就会结束，所以要先让浮窗挂着。",
                actionLabel = "开始配对",
                action = { startPairingFlow() },
                detect = { XpqAdbShell.isConnected() },
            ),
            InlineStep(
                title = "自动连接",
                brief = "配对好后自己连上",
                body = "配对成功后会自动连接并关闭浮窗。以后启动/重启都会自己重连，" +
                        "只有首次需要配对。",
                detect = { XpqAdbShell.isConnected() },
            ),
        )

        // Shizuku 模式步骤：检测项 = 服务在跑 / 已授权
        val shizukuMerged = AutomationShizuku.isManagerMergedIntoHost()
        val shizukuSteps = listOf(
            InlineStep(
                title = if (shizukuMerged) "启动 Shizuku 服务" else "安装并启动 Shizuku",
                brief = if (shizukuMerged) "本软件已内置 Shizuku 管理器，直接启动服务"
                else "装好并打开，按提示启动服务",
                body = if (shizukuMerged)
                    "本软件已内置 Shizuku 管理器，无需额外下载。点「打开 Shizuku」，" +
                            "按里面的提示启动服务即可（Android 11 以上可用「无线调试」启动）。"
                else
                    "下载安装后打开 Shizuku，按提示启动服务（Android 11 以上可用「无线调试」启动）。",
                links = if (shizukuMerged) listOf(
                    "shizuku 使用教程" to "https://mp.weixin.qq.com/s/qG3znEmRgtOkfmktM2mxrA"
                ) else listOf(
                    "官方原版下载" to "https://github.com/RikkaApps/Shizuku/releases",
                    "备用原版下载" to "https://apt.izzysoft.de/fdroid/index/apk/moe.shizuku.privileged.api",
                    "最新修改版下载（推荐）" to "https://github.com/thedjchi/Shizuku/releases",
                    "shizuku 使用教程" to "https://mp.weixin.qq.com/s/qG3znEmRgtOkfmktM2mxrA",
                ),
                actionLabel = if (shizukuMerged) "打开内置 Shizuku" else "打开 Shizuku",
                action = { AutomationShizuku.openShizuku(activity) },
                detect = { AutomationShizuku.isInstalled() },
            ),
            InlineStep(
                title = "授权本应用",
                brief = "在 Shizuku 弹窗里点「允许」",
                body = "点「立即授权」，在 Shizuku 弹出的授权框里选「允许」。授权一次后长期有效。",
                actionLabel = "立即授权",
                action = { AutomationShizuku.requestPermission { } },
                detect = { AutomationShizuku.isPermissionGranted() },
            ),
        )

        val (wifiStepsView, refreshWifiSteps) = buildInlineSteps(wifiSteps)
        val (shizukuStepsView, refreshShizukuSteps) = buildInlineSteps(shizukuSteps)
        wifiStepsView.visibility = View.GONE
        shizukuStepsView.visibility = View.GONE

        // 「打开WiFi设置界面」行：标题 + 说明 + 右侧状态标记（✓ / ×）；点行就地展开 / 收起步骤清单
        val guideStatus = buildStatusBadge()
        fun wifiGuideReady(): Boolean = globalOn(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) &&
                globalOn(Settings.Global.ADB_ENABLED) &&
                globalOn("adb_wifi_enabled") &&
                XpqAdbShell.isConnected()
        fun refreshGuideSwitch() {
            renderStatus(guideStatus, wifiGuideReady())
        }
        refreshGuideSwitch()
        val guideLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "打开WiFi设置界面"
                textSize = 14f
                setTextColor(0xFF212121.toInt())
            })
            addView(TextView(activity).apply {
                text = "点开查看步骤，全部完成后显示「✓」"
                textSize = 12f
                setTextColor(0xFF757575.toInt())
            })
        }
        val guideRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            addView(
                guideLabels,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(guideStatus)
            setOnClickListener {
                val expand = wifiStepsView.visibility != View.VISIBLE
                wifiStepsView.visibility = if (expand) View.VISIBLE else View.GONE
                if (expand) refreshWifiSteps()
            }
        }
        // 系统开关（开发者选项 / USB 调试 / 无线调试）变化时同步刷新状态与步骤
        val guideSettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshGuideSwitch()
                refreshWifiSteps()
            }
        }

        // WiFi 模式面板：现在只留高级设置入口（当前隐藏，恢复时去掉 adbAdvancedRow 的 GONE 即可）
        val builtinPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(), (4 * density).toInt(),
                (20 * density).toInt(), (8 * density).toInt()
            )
            visibility = if (currentIndex == 2) View.VISIBLE else View.GONE
            addView(guideRow)
            addView(
                wifiStepsView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
            )
            addView(
                adbAdvancedRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
            )
        }

        // 「打开 Shizuku 设置界面」与 WiFi 模式同款：点行就地展开 / 收起步骤清单
        val shizukuStatus = buildStatusBadge()
        fun shizukuReady(): Boolean = runCatching {
            AutomationShizuku.isInstalled() && AutomationShizuku.isPermissionGranted()
        }.getOrDefault(false)
        fun refreshShizukuSwitch() {
            renderStatus(shizukuStatus, shizukuReady())
        }
        refreshShizukuSwitch()
        val shizukuLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "打开 Shizuku 设置界面"
                textSize = 14f
                setTextColor(0xFF212121.toInt())
            })
            addView(TextView(activity).apply {
                text = "点开查看步骤，全部完成后显示「✓」"
                textSize = 12f
                setTextColor(0xFF757575.toInt())
            })
        }
        val shizukuRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(
                (20 * density).toInt(), (4 * density).toInt(),
                (20 * density).toInt(), (4 * density).toInt()
            )
            addView(
                shizukuLabels,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(shizukuStatus)
            setOnClickListener {
                val expand = shizukuStepsView.visibility != View.VISIBLE
                shizukuStepsView.visibility = if (expand) View.VISIBLE else View.GONE
                if (expand) refreshShizukuSteps()
            }
        }
        val shizukuPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentIndex == 1) View.VISIBLE else View.GONE
            addView(shizukuRow)
            // 与上面的标题行同一左边距（这个面板本身没有 padding，行内是 20dp）
            addView(
                shizukuStepsView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                    marginStart = (20 * density).toInt()
                    marginEnd = (20 * density).toInt()
                }
            )
        }

        // setView 只能挂一个 View：两个面板放进同一个容器，按选项切换显隐
        val customPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // 顺序：说明文字 → 开启Shizuku模式 / 开启WiFi模式 → 两个开关（入口行在开关之上）
            addView(titleView)
            addView(shizukuPanel)
            addView(builtinPanel)
            addView(switchPanel)
        }
        switchPanelRef = switchPanel
        builtinPanelRef = builtinPanel
        shizukuPanelRef = shizukuPanel
        if (currentIndex == 0) switchPanel.visibility = View.GONE

        // 自定义面板整体限高 + 可滚动：行内步骤展开后内容会很高，
        // 不限高会把「确定 / 取消」按钮挤出屏幕（部分机型弹窗不自动收缩）。
        // 上限取屏高的 45%，剩下的空间留给标题、选项列表和底部按钮。
        val customPanelScroll = MaxHeightScrollView(
            activity, (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()
        ).apply {
            addView(customPanel)
        }
        // 右侧「还有内容」滑动提示：样式与库内既有提示（XPQAccUtils 的「滑动到底关闭」）一致，
        // 内容能继续下滚时才显示，滑到底自动消失。
        val scrollHint = TextView(activity).apply {
            // 竖排（每行一个字），只占很窄的一条，不挡内容
            text = "↕\n向\n下\n滑\n动"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(
                (8 * density).toInt(), (10 * density).toInt(),
                (8 * density).toInt(), (10 * density).toInt()
            )
            background = GradientDrawable().apply {
                setColor(0xCC555555.toInt())
                cornerRadius = 20 * density
            }
            elevation = 8f
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        fun updateScrollHint() {
            scrollHint.visibility =
                if (customPanelScroll.canScrollVertically(1)) View.VISIBLE else View.GONE
        }
        customPanelScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateScrollHint() }
        // 步骤展开 / 收起、切换模式后高度变化都会走这里的全局布局，顺便刷新提示
        customPanelScroll.viewTreeObserver.addOnGlobalLayoutListener { updateScrollHint() }
        val panelFrame = FrameLayout(activity).apply {
            addView(
                customPanelScroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                scrollHint,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    rightMargin = (4 * density).toInt()
                }
            )
        }

        // 锁定浅色主题：单选列表/按钮/链接色全部固定，不随系统暗黑模式变化（见 titleView 处注释）
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            // 标题（模式名）固定在顶部居中；说明文字在 customPanel 底部
            .setCustomTitle(headerView)
            .setView(panelFrame)
            .setSingleChoiceItems(items, currentIndex) { _, which ->
                selected = which
                // 切换选项时同步刷新说明文字，并隐藏/显示 Shizuku 相关的两个开关
                renderMode(which)
            }
            .setPositiveButton("确定") { _, _ ->
                val mode = indexToMode(selected)
                // 第三项 = 内置 ADB 优先；其它两项关闭该偏好，避免上次选择残留
                XpqAdbShell.setPreferred(activity, selected == 2)
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
        modeDialogRef = dialog

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

        // 「开启随选朗读」开关联动：改变它等于改变「本次是否走独占开启」→ 只需刷新两个开关行的显隐。
        // （说明文字已与开关无关 —— 原来跟着开关出现/消失的「关闭其它无障碍…」提示已删除；
        //   若日后重新引入开关相关的文案，记得把 renderMode(selected) 加回来。）
        // 注意本监听在 isChecked 初值设好之后才挂，避免初始化时触发一次无意义回调。
        selectToSpeakSwitch.setOnCheckedChangeListener { _, checked ->
            selectToSpeakOn = checked
            updateThumbLabel(selectToSpeakThumb, checked)
            updateSwitchRowVisibility(selected)
        }
        // 「同时开启本应用无障碍」开关：只跟随刷新圆钮里的「开 / 关」（取值仍由点「确定」时统一写回）
        hostAccSwitch.setOnCheckedChangeListener { _, checked ->
            updateThumbLabel(hostAccThumb, checked)
        }

        // 弹窗显示期间低频刷新两个入口行的状态：Shizuku 授权 / ADB 连接状态都不会触发 ContentObserver
        val stateHandler = Handler(Looper.getMainLooper())
        val stateTick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                refreshGuideSwitch()
                refreshShizukuSwitch()
                // 行内步骤：只在检测结果变化时才会重建（内部有签名比对）
                refreshWifiSteps()
                refreshShizukuSteps()
                stateHandler.postDelayed(this, 1000)
            }
        }

        dialog.setOnShowListener {
            // 部分 ROM（MIUI/EMUI 等）定制主题下按钮文字为浅色，浅底上看不见，显式锁深色
            val btnColor = 0xFF212121.toInt()
            runCatching {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(btnColor)
            }
            // 打开时先按当前系统状态同步两个入口行的开关
            refreshGuideSwitch()
            refreshShizukuSwitch()
            runCatching {
                listOf(
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    Settings.Global.ADB_ENABLED,
                    "adb_wifi_enabled",
                ).forEach {
                    activity.contentResolver.registerContentObserver(
                        Settings.Global.getUriFor(it), false, guideSettingsObserver
                    )
                }
            }
            // Shizuku 授权 / 连接状态不是系统设置项，弹窗显示期间低频轮询（值没变时不触发重绘）
            stateHandler.postDelayed(stateTick, 1000)
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
        dialog.setOnDismissListener {
            stateHandler.removeCallbacks(stateTick)
            runCatching {
                activity.contentResolver.unregisterContentObserver(guideSettingsObserver)
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

        // ⚠ 本方法是**异步**回调：要等连接尝试彻底结束才走到这里，中间隔着 permission 弹窗、UIA 连接、
        // 无障碍名单写入等一堆耗时动作。期间宿主 Activity 完全可能已经 finish 或重建
        // （用户返回、屏幕旋转、切模式后重启进程…），此时再建 Dialog 会在 show() 抛
        // WindowManager.BadTokenException("token ... is not valid; is your activity running?")；
        // 而这又是抛在主线程回调里、外面没有 try/catch —— 直接崩掉整个应用。
        // Activity 已不可用就降级成 toast，绝不能为了"把提示弹出来"把 App 带崩。
        if (activity.isFinishing || activity.isDestroyed) {
            val prefix = if (XpqAdbShell.isPreferred(activity)) "WiFi 模式" else "WiFi 模式（Shizuku）"
            AliveUtils.toast(msg = "$prefix 连接失败：$msg")
            return
        }

        // 组件被禁用与「Shizuku 没开」是两种成因，按钮动作完全不同：
        // 前者点「打开 Shizuku」毫无作用，只能恢复组件 + 重启应用（组件恢复后 server 会重新投递 binder）。
        val builtinMode = XpqAdbShell.isPreferred(activity)
        val componentDisabled = msg == REASON_SHIZUKU_COMPONENT_DISABLED
        val needShizuku = !builtinMode && !componentDisabled && msg.contains("Shizuku", ignoreCase = true)
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(if (builtinMode) "WiFi 模式 连接失败" else "WiFi 模式（Shizuku）连接失败")
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
        // 上面的存活检查与 show() 之间仍存在极小竞态（同一帧内被 finish），兜一层，任何情况下都不许崩
        if (runCatching { dialog.show() }.isFailure) {
            AliveUtils.toast(msg = "WiFi 模式（Shizuku）连接失败：$msg")
            return
        }
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
                "简单说，在wx中，黑名单中的软件无法正常工作，只有白名单中的软件才能正常工作，所以我们要" +
                "\n开启系统自带的  \n随选朗读「作用：保证 WiFi 模式下在wx里正常工作」" +
                "\n关闭其它所有的无障碍服务「作用：降低被检测到概率」"
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("为何需要 随选朗读")
            .setMessage(msg)
            .setPositiveButton("知道了") { _, _ ->
                //NotificationUtilXpq.gotoAccessibilitySetting(activity)
            }
            .setNegativeButton("取消"){ _, _ ->
                //viewUrl()
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
    fun showHostAccDialog(activity: Activity, imgRes: Int = R.drawable.backgroundshow_xpq) {
        val msg = "本开关决定：切换到 WiFi 模式时，是否同时打开【无障碍模式】。\n\n" +
                "WiFi模式下。一般不需要再开本应用自己的无障碍" +
                "\n· 1.当用到边缘手势功能的时候，需要打开" +
                "\n· 2.当某些场景点击没效果的时候，可以尝试打开" +
                "\n\n建议先 手动打开 一次，解除 受限制的设置"
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("为何需要 本应用无障碍")
            .setMessage(msg)
            .setPositiveButton("知道了") { _, _ ->
                //NotificationUtilXpq.gotoAccessibilitySetting(activity)
            }
            .setNegativeButton("取消"){ _, _ ->
                //viewUrl()
            }
            // 部分机型（Android 13+ 侧载安装）里无障碍开关是灰的「受限设置」，
            // 库内已有说明入口：跳微信文章看解决方法
            .setNeutralButton("手动打开") { _, _ ->
                //AliveUtils.shouxianzhi(activity)
                showCheckDialog(
                    activity,
                    R.string.wzaxpq,
                    imgRes,
                    R.string.quanxian0,
                    MMKVConst.BTN_ACCESSIBILITY
                )
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
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(btnColor)
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

/**
 * 把「开 / 关」画进 SwitchCompat 的圆钮里：包住原 thumb，绘制时在其 bounds 中央叠加文字。
 *
 * 圆钮的左右移动是 SwitchCompat 通过不断设置 thumbDrawable 的 bounds 实现的（不是平移画布），
 * 所以这里只要跟着转写 bounds，文字就自动贴着圆钮走，开关滑动动画也不用手动处理。
 * 其余绘制细节（颜色、状态、intrinsic 尺寸）全部委托给原 thumb，外观保持不变。
 */
private class ThumbTextDrawable(private val base: Drawable) : Drawable(), Drawable.Callback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    // 白色描边打底：无论圆钮是浅色还是主题色，字都看得清（部分 ROM 的开关圆钮始终是白色）
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
    }

    override fun invalidateDrawable(who: Drawable) {
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        scheduleSelf(what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        unscheduleSelf(what)
    }

    var label: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    var labelColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            paint.color = value
            invalidateSelf()
        }

    var labelSize: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            paint.textSize = value
            invalidateSelf()
        }

    init {
        // 原 thumb 的失效通知转到本 Drawable，最终仍由 SwitchCompat 触发重绘
        base.callback = this
    }

    override fun draw(canvas: Canvas) {
        base.draw(canvas)
        if (label.isEmpty() || labelSize <= 0f) return
        val b = bounds
        // 文字基线：让文字视觉居中于圆钮（考虑 ascent/descent，而不是简单的 bounds 中心）
        val baseline = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2f
        halo.textSize = paint.textSize
        halo.strokeWidth = (paint.textSize * 0.25f).coerceAtLeast(2f)
        canvas.drawText(label, b.exactCenterX(), baseline, halo)
        canvas.drawText(label, b.exactCenterX(), baseline, paint)
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        base.setBounds(left, top, right, bottom)
    }

    override fun setBounds(bounds: Rect) {
        super.setBounds(bounds)
        base.bounds = bounds
    }

    override fun getIntrinsicWidth(): Int = base.intrinsicWidth

    override fun getIntrinsicHeight(): Int = base.intrinsicHeight

    // SwitchCompat 用「intrinsicWidth - thumb.getPadding()」算圆钮宽度，少这一层转发会让宽度凭空变大，
    // 进而把整条轨道（背景）撑长，所以必须原样转发。
    override fun getPadding(padding: Rect): Boolean = base.getPadding(padding)

    override fun setAlpha(alpha: Int) {
        base.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        base.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = base.opacity

    override fun isStateful(): Boolean = base.isStateful

    override fun onStateChange(state: IntArray): Boolean = base.setState(state)

    override fun setHotspot(x: Float, y: Float) {
        base.setHotspot(x, y)
    }

    override fun jumpToCurrentState() {
        base.jumpToCurrentState()
    }
}

/** 高度随内容走，但不超过 [maxHeight]：步骤少时不留空白，多了才滚动。 */
private class MaxHeightScrollView(
    context: Context,
    private val maxHeight: Int,
) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        )
    }
}
