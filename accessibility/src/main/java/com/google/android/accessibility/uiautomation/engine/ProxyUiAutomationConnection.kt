package com.google.android.accessibility.uiautomation.engine

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.google.android.accessibility.uiautomation.util.HiddenApi
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 与 gkd-kit/gkd 完全对齐的「连接代理」。
 *
 * 关键机制（之前一直错的地方）：
 *  - 本对象【不是】在 shell 进程里跑的 Stub，而是 App 进程内承载 dynamic Proxy 转发逻辑的辅助器；
 *  - `handleConnect` 里拿的是 **ShizukuBinderWrapper 包裹**的系统 AccessibilityManager binder，
 *    所有 transact 都被 Shizuku 路由到 shell server（uid 2000），于是
 *    `registerUiTestAutomationService` 是以【shell 身份】发出的——这正是 GKD 的
 *    `PrivilegeBinderWrapper.fromSystemService` 做的事；
 *  - 之前走 Shizuku UserService 的 `registerUiAutomation` RPC，而 UserService 进程实际是 **app uid**，
 *    ServiceManager.getService 拿到的 IAM 是 app 身份，system_server 直接拒绝注册 → init 永不回调 → 5 秒超时。
 *
 * framework 传入的 `client`（IAccessibilityServiceClient IBinder）在进程内直接透传给
 * registerUiTestAutomationService，不再二次跨进程。
 */
class ProxyUiAutomationConnection(
    private val onLog: (String) -> Unit = {}
) : Binder() {

    private val tag = "ProxyUiAutoConn"

    /**
     * 本对象【本身就是】framework 的 `IUiAutomationConnection` 承载者：extends Binder 并 override
     * onTransact，直接响应 framework 经 `Stub.asInterface(...).connect()` 发来的 binder 事务。
     *
     * 为什么不是 dynamic Proxy / 不是 extends IUiAutomationConnection.Stub()？
     *  - 本库无 GKD 的 `@RemapType` 插件，无法把 `IUiAutomationConnection` 重定向到
     *    bootclasspath 真实类，直接 `extends Stub()` 运行期会 NoSuchMethodError；
     *  - dynamic Proxy 的 `asBinder()` 只能返回一个 plain Binder，framework `Stub.asInterface`
     *    会再包一层 AOSP Proxy，其 `connect()` 走 transact 落到 plain Binder 的默认 onTransact
     *    （什么都不做）→ handleConnect 永不被执行 → init 永不回调 → wins=0 / 5 秒超时。
     *  - 故采用「extends Binder + override onTransact」：`asInterface(this)` 生成的 AOSP Proxy
     *    其 connect 事务会正确 transact 回本对象的 onTransact → handleConnect。
     *
     * 外层仍用 dynamic Proxy 包一层只为类型适配（满足 UiAutomation 构造器的
     * `IUiAutomationConnection` 形参类型），该代理的 `asBinder()` 返回【本对象】。
     */
    private val ownerToken: IBinder = Binder()

    companion object {
        private const val DESCRIPTOR = "android.app.IUiAutomationConnection"
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            // FIRST_CALL_TRANSACTION(1) = connect
            IBinder.FIRST_CALL_TRANSACTION -> {
                data.enforceInterface(DESCRIPTOR)
                val client = data.readStrongBinder()
                val f = data.readInt()
                try {
                    handleConnect(client, f)
                    reply?.writeNoException()
                } catch (t: Throwable) {
                    reply?.writeException(t as? Exception ?: RuntimeException(t))
                }
                true
            }
            // +1(2) = disconnect
            IBinder.FIRST_CALL_TRANSACTION + 1 -> {
                data.enforceInterface(DESCRIPTOR)
                try {
                    handleDisconnect()
                    reply?.writeNoException()
                } catch (t: Throwable) {
                    reply?.writeException(t as? Exception ?: RuntimeException(t))
                }
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    @Volatile
    var clientBinderRef: IBinder? = null
        private set

    /** 注册时实际使用的 AccessibilityServiceInfo.flags（供 getRoot 诊断对比"发出" vs "系统回报"）。 */
    @Volatile
    var registeredFlags: Int = 0
        private set

    /** 注册时使用的 userId（前台窗口通常在 user 0；若用错 userId 会导致 windows=0）。 */
    @Volatile
    var registeredUserId: Int = 0
        private set

    /** 注册时使用的 connectFlags（含 FLAG_ALLOW_MONITORED_IMMEDIATE_TREE_OBSERVATION，供诊断）。 */
    @Volatile
    var registeredConnectFlags: Int = 0
        private set

    /** 注册用的 AccessibilityServiceInfo（供 InvisibleAutomation 在 connect 完成后做 setServiceInfo 兜底）。 */
    @Volatile
    var infoForSet: AccessibilityServiceInfo? = null
        private set

    /** 反射取 flag（compileSdk 36 桩可能不暴露或值变）。 */
    private fun flagOrNull(name: String, fallback: Int): Int = runCatching {
        AccessibilityServiceInfo::class.java.getField(name).getInt(null)
    }.getOrElse { fallback }

    /**
     * 由 dynamic Proxy 的 connect 分支调用。任何异常都立即上抛，让 framework 的 connect()
     * 把真实错误冒到 UI（不再静默 5 秒超时）。
     */
    fun handleConnect(client: IBinder, flags: Int) {
        clientBinderRef = client
        val log = { s: String ->
            Log.i(tag, s)
            runCatching { onLog("    [proxy] $s") }
        }
        log("handleConnect client=$client flags=0x${Integer.toHexString(flags)}")

        try {
            HiddenApi.ensure()

            // 1) 取系统 AccessibilityManager 原始 binder，用 ShizukuBinderWrapper 包裹 → shell 身份
            val raw = SystemServiceHelper.getSystemService("accessibility")
            if (raw == null) {
                throw IllegalStateException("ServiceManager.getService(\"accessibility\") 返回 null")
            }
            val shellBinder: IBinder = ShizukuBinderWrapper(raw)
            val iam = HiddenApi.asInterfaceIAccessibilityManager(shellBinder)

            // 2) 构造 AutomationServiceInfo。
            // 与 GKD `UiAutomationServiceInfo.kt` 完全对齐：flags 与 capabilities **同时设**——
            // system_server 端 `canRetrieveWindowsLocked` 实际看 capabilities
            // (`getCapabilities() & CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT`)，不是 flags！
            // 之前一直只设 flags 不设 capability，wins 永远=0，根因。
            val info = AccessibilityServiceInfo().apply {
                eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK
                packageNames = null
                feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                notificationTimeout = 100
                this.flags = HiddenApi.FLAG_RETRIEVE_WINDOW_CONTENT or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        HiddenApi.FLAG_IS_AUTOMATION_TOOL or
                        HiddenApi.FLAG_FORCE_DIRECT_BOOT_AWARE
            }
            // **关键**：通过反射写 capabilities（GKD `toHidden.setCapabilities(rawInfo.capabilities)`）。
            val capabilities = HiddenApi.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT or
                    HiddenApi.CAPABILITY_CAN_PERFORM_GESTURES or
                    HiddenApi.CAPABILITY_CAN_TAKE_SCREENSHOT
            HiddenApi.setCapabilities(info, capabilities)
            // Android 14+ 必须调 setAccessibilityTool(true)，部分 ROM 缺这个标志会拒绝为工具类
            // UiAutomation 返回窗口（GKD 同步行为）。
            HiddenApi.setAccessibilityTool(info, true)
            registeredFlags = info.flags
            val userId = HiddenApi.currentUserId()
            registeredUserId = userId

            // 3) 在 App 进程内、以 shell 身份注册。
            // 完全对齐 gkd-kit/gkd：直接用 framework 传入的 flags（FLAG_DONT_SUPPRESS=1），
            // 不额外 OR FLAG_ALLOW_MONITORED_IMMEDIATE_TREE_OBSERVATION(0x4)。GKD 验证过纯
            // flags=1 即可让 system_server 正常注册并回调 init；多 OR 0x4 反而可能在部分 ROM
            // 上被忽略或改变注册行为，无必要。
            val connectFlags = flags
            registeredConnectFlags = connectFlags
            infoForSet = info
            val accepted = HiddenApi.registerUiTestAutomationService(
                iam = iam,
                owner = ownerToken,
                client = client,
                info = info,
                flags = connectFlags,
                userId = userId,
            )
            if (!accepted) {
                throw RuntimeException(
                    "system_server 拒绝注册（registerUiTestAutomationService 返回非 true）"
                )
            }
            log("handleConnect: 注册被接受，等待 system_server 回调 init")
        } catch (t: Throwable) {
            Log.e(tag, "handleConnect 失败", t)
            runCatching { onLog("    [proxy] handleConnect THROW: ${t.javaClass.name}: ${t.message}") }
            // 包成 RuntimeException 抛给 framework connect()，UI 可见真实原因
            throw RuntimeException("registerUiTestAutomationService 失败: ${t.message}", t)
        }
    }

    fun handleDisconnect() {
        val cb = clientBinderRef ?: run {
            Log.i(tag, "handleDisconnect: 无 client 引用，跳过")
            return
        }
        try {
            HiddenApi.ensure()
            val raw = SystemServiceHelper.getSystemService("accessibility")
            val shellBinder: IBinder = ShizukuBinderWrapper(raw)
            val iam = HiddenApi.asInterfaceIAccessibilityManager(shellBinder)
            HiddenApi.unregisterUiTestAutomationService(iam, cb)
            Log.i(tag, "handleDisconnect: 反注册 OK")
        } catch (t: Throwable) {
            Log.w(tag, "handleDisconnect 失败", t)
        } finally {
            clientBinderRef = null
        }
    }
}
