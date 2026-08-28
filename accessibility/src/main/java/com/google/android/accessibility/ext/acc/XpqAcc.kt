package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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

    @Volatile
    @JvmStatic
    var driver: AccDriver = a11yDriver
        private set

    @JvmStatic
    val mode: EngineMode get() = driver.mode

    @JvmStatic
    val isConnected: Boolean get() = driver.isConnected

    /** 切换通道；切换时断开旧引擎，并同步 accessibilityService 全局变量指向。 */
    @JvmStatic
    fun use(mode: EngineMode) {
        if (driver.mode == mode) return
        runCatching { driver.disconnect() }
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
     */
    @JvmStatic
    @JvmOverloads
    fun connectUiAutomation(
        onLog: (String) -> Unit = {},
        onResult: (success: Boolean, reason: String?) -> Unit = { _, _ -> }
    ) {
        use(EngineMode.UIAUTOMATION)
        val main = Handler(Looper.getMainLooper())
        val log: (String) -> Unit = { s -> main.post { onLog(s) } }
        Thread {
            // 连接前先清理旧连接，避免 system_server 残留注册导致 "already registered" 失败
            runCatching { disconnect() }
            if (!isShizukuRunning()) {
                log("✗ Shizuku 未运行，请先启动 Shizuku")
                main.post { onResult(false, "Shizuku 未运行") }
                return@Thread
            }
            if (!isShizukuPermissionGranted()) {
                log("Shizuku 未授权，请求授权中…")
                // 授权弹窗需在主线程
                main.post {
                    requestShizukuPermission { granted ->
                        if (granted) {
                            log("Shizuku 授权成功，开始连接…")
                            Thread {
                                val ok = connect(log)
                                main.post { onResult(ok, if (ok) null else (UiAutomationDriver.lastError ?: "连接失败")) }
                            }.start()
                        } else {
                            log("✗ Shizuku 授权被拒绝")
                            main.post { onResult(false, "Shizuku 授权被拒绝") }
                        }
                    }
                }
                return@Thread
            }
            val ok = connect(log)
            main.post { onResult(ok, if (ok) null else (UiAutomationDriver.lastError ?: "连接失败")) }
        }.start()
    }

    // ---- 门面透传 ----
    @JvmStatic
    @JvmOverloads
    fun connect(onLog: (String) -> Unit = {}) = driver.connect(onLog)

    @JvmStatic
    fun disconnect() = driver.disconnect()

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
        setOnAccessibilityEventListener { event -> handler.onAccessibilityEvent(event) }
    }
}
