package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.MMKVUtil
import com.google.android.accessibility.ext.window.DynamicIslandFloatWindow
import com.google.android.accessibility.notification.AppExecutors
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.google.android.accessibility.selecttospeak.accessibilityServiceLiveData
import com.google.android.accessibility.uiautomation.shizuku.AutomationShizuku

/**
 * 无障碍能力门面：宿主在 App 启动时一次性调用 [use] 选择通道（无障碍 / UiAutomation），
 * 之后业务代码零改动。
 *
 * 切换原理：UiAutomation 模式下把全局 accessibilityService 指向 [ProxyAccessibilityService]
 * （覆盖取根/窗口/焦点委托给 UiAutomation），无障碍模式下恢复真实 service 实例。
 * 节点查询/点击天然跨通道，performGlobalAction / dispatchGesture 由库内 3 处封装点走本门面。
 */
object XpqAcc {

    private val a11yDriver: AccDriver = A11yDriver
    private val uiDriver: AccDriver = UiAutomationDriver
    private val proxyService by lazy { ProxyAccessibilityService() }

    /** 事件桥接的宿主服务实例（UiAutomation 模式手动 new），用于断开时触发销毁回调释放资源。 */
    @Volatile
    private var bridgedHandler: SelectToSpeakServiceAbstract? = null

    /** 断开 UiAutomation 连接时统一收尾：触发宿主销毁回调并清引用。 */
    private fun teardownUiAutomationBridge() {
        val h = bridgedHandler
        bridgedHandler = null
        h?.let { runCatching { it.onUiAutomationDestroy() } }
    }

    @Volatile
    @JvmStatic
    var driver: AccDriver = a11yDriver
        private set

    @JvmStatic
    val mode: EngineMode get() = driver.mode

    @JvmStatic
    val isConnected: Boolean get() = driver.isConnected

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
            EngineMode.UIAUTOMATION -> proxyService
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

    /** Shizuku 是否在运行（未运行则 UiAutomation 通道无法连接，需引导用户先启动 Shizuku）。 */
    @JvmStatic
    fun isShizukuRunning(): Boolean = AutomationShizuku.isInstalled()

    /** 本 App 是否已获得 Shizuku 授权。 */
    @JvmStatic
    fun isShizukuPermissionGranted(): Boolean = AutomationShizuku.isPermissionGranted()

    /** 请求 Shizuku 授权（Shizuku 会弹出授权界面），结果经回调返回 granted 是否成功。 */
    @JvmStatic
    fun requestShizukuPermission(onResult: (granted: Boolean) -> Unit) =
        AutomationShizuku.requestPermission(onResult)

    /**
     * 一步到位：切 UiAutomation + 检测/请求 Shizuku + 连接。
     *
     * 连接本身是阻塞操作（绑定 Shizuku UserService 最多等 10s、UiAutomation.connect 最多等 5s），
     * 故本方法内部在后台线程执行连接，onLog / onResult 都切回主线程回调，宿主可放心在主线程调用。
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
        onResult: (success: Boolean, reason: String?) -> Unit = { _, _ -> },
        activity: Activity? = null,
        bridgeFallback: SelectToSpeakServiceAbstract? = null
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
                main.post { onResult(true, null) }
            } else {
                fail(UiAutomationDriver.lastError ?: "连接失败")
            }
        }
        Thread {
            // 连接前先清理旧连接，避免 system_server 残留注册导致 "already registered" 失败
            runCatching { disconnect() }
            if (!isShizukuRunning()) {
                log("✗ Shizuku 未运行，请先启动 Shizuku")
                fail("Shizuku 未运行")
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

    @JvmStatic
    fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?) =
        driver.setOnAccessibilityEventListener(listener)

    /**
     * 返回当前通道对应的 service 实例：
     * 无障碍模式 = 真实无障碍服务实例（服务未开启时为 null）；
     * UiAutomation 模式 = [ProxyAccessibilityService]（非 null）。
     * 用于给 XPQEventData.service 等需要 AccessibilityService 的地方提供通道感知的值。
     */
    @JvmStatic
    fun currentService(): AccessibilityService? = when (driver.mode) {
        EngineMode.ACCESSIBILITY_SERVICE -> SelectToSpeakServiceAbstract.instance
        EngineMode.UIAUTOMATION -> proxyService
    }

    /**
     * 事件桥接：把当前通道的无障碍事件转发给指定的事件处理器（宿主继承 [SelectToSpeakServiceAbstract] 的实例）。
     * 直接调用 [SelectToSpeakServiceAbstract.onAccessibilityEvent]（多态分派），宿主 override 的 onAccessibilityEvent 也会执行；
     * 基类实现内部会 dealEvent → asyncHandle_XXX + asyncHandleAccessibilityEvent + listeners，与无障碍模式等价。
     * 无障碍模式无需调用（系统直接回调 onAccessibilityEvent）；UiAutomation 模式下手动 new 一个服务实例传入即可。
     */
    @JvmStatic
    fun bridgeAccessibilityEvent(handler: SelectToSpeakServiceAbstract) {
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
        val handler = findAccessibilityServiceSubclass() ?: fallback
        if (handler == null) {
            android.util.Log.w("XpqAcc", "自动事件桥接失败：未找到宿主无障碍服务子类，且无兜底实例")
            return false
        }
        // 手动 new 的实例不会被系统绑定（onServiceConnected 不回调），这里手动触发宿主的
        // 就绪钩子，把通道无关的初始化（如屏幕广播接收器）补上。service 传 proxyService。
        // 同时保存引用，供断开连接时触发 onUiAutomationDestroy 释放资源。
        bridgedHandler = handler
        runCatching { handler.onUiAutomationReady(currentService()) }
        bridgeAccessibilityEvent(handler)
        android.util.Log.i("XpqAcc", "自动事件桥接成功：${handler.javaClass.name}")
        return true
    }

    /** 从宿主 App 的 Manifest 反射实例化声明了 BIND_ACCESSIBILITY_SERVICE 的服务子类。 */
    private fun findAccessibilityServiceSubclass(): SelectToSpeakServiceAbstract? {
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
     */
    @JvmStatic
    fun applyEngineMode(
        mode: EngineMode,
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
                connectUiAutomation(onLog = {}, onResult = onResult)
            }
        }
    }

    /**
     * 弹窗选择自动化通道（无障碍 / UiAutomation），选择后持久化并立即应用。
     * 宿主可把它挂在某个按钮/设置项上，App 启动时再调 [loadEngineMode] + [applyEngineMode] 恢复上次选择。
     */
    @JvmStatic
    fun showEngineModeDialog(activity: Activity) {
        val items = arrayOf("无障碍模式", "Shizuku 模式")
        val current = loadEngineMode().ordinal

        // 说明文字放到标题区（setMessage 与 setSingleChoiceItems 互斥，用了 setMessage 选项列表就不显示）
        val density = activity.resources.displayMetrics.density
        val titleView = TextView(activity).apply {
            text = "选择自动化通道\n\n" +
                    "Shizuku 模式需要额外下载一个免费开源的 Shizuku 软件。\n" +
                    "为什么引入该模式：\n" +
                    "部分应用会检测设备上启用的无障碍服务，并可能限制自动化功能。" +
                    "Shizuku 模式使用不同的系统权限通道，可以作为另一种自动化方案"
            textSize = 14f
            setPadding(
                (20 * density).toInt(),
                (16 * density).toInt(),
                (20 * density).toInt(),
                (8 * density).toInt()
            )
        }

        AlertDialog.Builder(activity)
            .setCustomTitle(titleView)
            .setSingleChoiceItems(items, current) { dialog, which ->
                dialog.dismiss()
                val mode = if (which == 0) EngineMode.ACCESSIBILITY_SERVICE else EngineMode.UIAUTOMATION
                applyEngineMode(mode) { success, reason ->
                    when {
                        success -> AliveUtils.toast(msg = "已切换到 ${items[mode.ordinal]}")
                        mode == EngineMode.UIAUTOMATION -> showUiAutomationFailDialog(activity, reason)
                        else -> AliveUtils.toast(msg = reason ?: "切换失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * UiAutomation 模式切换失败时弹引导对话框（无论失败原因，一律弹窗而非 toast）。
     * 内容显示真实失败原因；若原因涉及 Shizuku（未运行/未授权/残留注册），带「打开 Shizuku」按钮跳转。
     */
    private fun showUiAutomationFailDialog(activity: Activity, reason: String?) {
        val msg = reason ?: "未知错误"
        val needShizuku = msg.contains("Shizuku", ignoreCase = true)
        AlertDialog.Builder(activity)
            .setTitle("Shizuku 模式连接失败")
            .setMessage(msg)
            .setPositiveButton(if (needShizuku) "打开 Shizuku" else "确定") { _, _ ->
                if (needShizuku && !AutomationShizuku.openShizuku(activity)) {
                    AliveUtils.toast(msg = "未找到 Shizuku 应用")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
