package com.google.android.accessibility.uiautomation.engine

import android.app.UiAutomation
import android.content.Context
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.uiautomation.shizuku.AutomationShizuku
import com.google.android.accessibility.uiautomation.shizuku.IAutomationUserService
import com.google.android.accessibility.uiautomation.util.HiddenApi
import android.graphics.Point
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 隐形自动化引擎（公开 API）。
 *
 * ## 机制（完全对齐 gkd-kit/gkd）
 *
 * - 用框架【真实】的 android.app.UiAutomation 类；构造时传入一个 dynamic Proxy「类型适配器」，
 *   其 `asBinder()` 返回 **ProxyUiAutomationConnection**（extends Binder + override onTransact）。
 * - framework 内部 `mUiAutomationConnection = IUiAutomationConnection.Stub.asInterface(conn.asBinder())`
 *   把这个 Binder 包成 AOSP Proxy；其 `connect()` 走 binder transact →
 *   **ProxyUiAutomationConnection.onTransact** → handleConnect（按事务码分支）。
 *   （这是无 @RemapType 插件下对齐 GKD extends Stub 的唯一可行形态；直接 dynamic Proxy 的
 *   asBinder 返回 plain Binder 会让 connect 永不路由到 handleConnect。）
 * - handleConnect 在【App 进程内】用
 *   `ShizukuBinderWrapper(SystemServiceHelper.getSystemService("accessibility"))` 拿到
 *   **shell 身份**的 IAccessibilityManager，直接调 registerUiTestAutomationService —— 等价于
 *   GKD 的 `PrivilegeBinderWrapper.fromSystemService`。client 是 framework 传入的本进程
 *   IAccessibilityServiceClient IBinder，进程内直接透传给 system_server；
 * - system_server 注册成功后会回调 `client.init(connectionId, windowToken)`（嵌套 binder 调用，
 *   发生在 connect 事务内），framework 内部把 mConnectionState 设为 CONNECTED，
 *   UiAutomation.connect() 的 mLock.wait(5000) 解除；framework 在 init 里自动调用
 *   AccessibilityInteractionClient.addConnection，故本库【无需】手动补 addConnection（GKD 也不补）。
 * - 之前几轮错误地走 Shizuku UserService RPC 注册，而 UserService 进程实际是 app uid，
 *   ServiceManager 拿到的 IAM 是 app 身份 → system_server 拒绝注册 → init 永不回调 → 5 秒超时。
 *   本次彻底改为 App 进程内 + ShizukuBinderWrapper（shell 身份）注册，与 GKD 一致。
 */
object InvisibleAutomation {

    private const val TAG = "InvisibleAuto"

    @Volatile
    private var mUiAutomation: UiAutomation? = null

    @Volatile
    private var mProxyHelper: ProxyUiAutomationConnection? = null

    @Volatile
    private var mSvc: IAutomationUserService? = null

    @Volatile
    private var mHandlerThread: HandlerThread? = null

    /** connect() 传入的 onLog，getRoot 诊断也复用它，让诊断直接显示在 App 日志面板（免 adb）。 */
    @Volatile
    private var uiLog: ((String) -> Unit)? = null

    val isConnected: Boolean
        get() = mUiAutomation != null

    /**
     * 全局动作常量，等价于 android.app.UiAutomation.GLOBAL_ACTION_*（与
     * AccessibilityService.GLOBAL_ACTION_* 同值）。底层 performGlobalAction 走的是
     * UiAutomation，因此从这里取常量，调用方无需再 import 任何 AccessibilityService 类。
     */
    val GLOBAL_ACTION_BACK: Int get() = uiAutoConst("GLOBAL_ACTION_BACK", 1)
    val GLOBAL_ACTION_HOME: Int get() = uiAutoConst("GLOBAL_ACTION_HOME", 2)
    val GLOBAL_ACTION_RECENTS: Int get() = uiAutoConst("GLOBAL_ACTION_RECENTS", 3)
    val GLOBAL_ACTION_NOTIFICATIONS: Int get() = uiAutoConst("GLOBAL_ACTION_NOTIFICATIONS", 4)
    val GLOBAL_ACTION_QUICK_SETTINGS: Int get() = uiAutoConst("GLOBAL_ACTION_QUICK_SETTINGS", 5)
    val GLOBAL_ACTION_POWER_DIALOG: Int get() = uiAutoConst("GLOBAL_ACTION_POWER_DIALOG", 6)
    val GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN: Int get() = uiAutoConst("GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN", 7)
    val GLOBAL_ACTION_LOCK_SCREEN: Int get() = uiAutoConst("GLOBAL_ACTION_LOCK_SCREEN", 8)

    private fun uiAutoConst(name: String, fallback: Int): Int =
        runCatching { UiAutomation::class.java.getField(name).getInt(null) }.getOrElse { fallback }

    @Volatile
    var lastError: String? = null
        private set

    /** UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES = 1（公开常量）。 */
    private val flagDontSuppress: Int
        get() = runCatching {
            UiAutomation::class.java
                .getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES").getInt(null)
        }.getOrElse { 1 }

    fun connect(
        context: Context,
        timeoutMs: Long = 15_000L,
        onLog: (String) -> Unit = {}
    ): Boolean = connectRetry(context, timeoutMs, onLog, 0)

    private fun connectRetry(
        context: Context,
        timeoutMs: Long,
        onLog: (String) -> Unit,
        attempt: Int
    ): Boolean {
        lastError = null
        uiLog = onLog
        HiddenApi.ensure()
        if (isConnected) {
            onLog("已处于连接状态，无需重复连接")
            return true
        }
        if (!AutomationShizuku.isPermissionGranted()) {
            lastError = "Shizuku 权限未授予"
            onLog("✗ $lastError")
            return false
        }
        onLog("[1/3] 绑定 Shizuku shell 服务（UserService）...")
        val svc = AutomationShizuku.bind(context.applicationContext, timeoutMs) {
            onLog("    $it")
        } ?: run {
            lastError = "bind() 返回 null：Shizuku UserService 绑定超时或被拒绝"
            onLog("✗ $lastError")
            return false
        }
        onLog("[2/3] Shizuku 服务已绑定 ✅")
        mSvc = svc

        return try {
            onLog("[3/3] 构造框架 UiAutomation 并 connect（App 进程内以 shell 身份注册 IAM）...")
            val handlerThread = HandlerThread("InvisibleAutoThread").also { it.start() }
            mHandlerThread = handlerThread
            val proxyHelper = ProxyUiAutomationConnection(onLog)
            mProxyHelper = proxyHelper

            // IUiAutomationConnection 是隐藏接口，编译期不可见，运行期 Class.forName 加载。
            val iConnClass = Class.forName("android.app.IUiAutomationConnection")
            // 外层 dynamic Proxy 只做【类型适配】：满足 UiAutomation 构造器的
            // `IUiAutomationConnection` 形参类型。framework 拿它后调 asBinder() 得到
            // proxyHelper（extends Binder + override onTransact 的真实 Binder 实例），
            // 再经 Stub.asInterface 包成 AOSP Proxy，其 connect() 走 transact →
            // proxyHelper.onTransact → handleConnect。（外层代理的 connect/disconnect 分支
            // 不会被 framework 直接调用，此处委托仅作保险。）
            val proxyAny = Proxy.newProxyInstance(
                iConnClass.classLoader,
                arrayOf(iConnClass),
                InvocationHandler { proxy, method, args ->
                    // 【调试关键】入口立即打 log（logcat + onLog）：可见 dynamic Proxy 是否被
                    // framework 调用、调了哪个方法、参数实际类型。任何异常也立即打 log + 抛出。
                    val argTypes = args?.map { it?.javaClass?.name ?: "null" } ?: listOf()
                    val msg = "INVOKE method=${method.name} argTypes=$argTypes"
                    Log.i(TAG, msg)
                    runCatching { onLog("    [invoke] $msg") }
                    when (method.name) {
                        "asBinder" -> {
                            // 返回真实 Binder 实例，framework 经它 transact 到 onTransact
                            proxyHelper
                        }
                        "connect" -> {
                            try {
                                val raw0 = args!![0]
                                runCatching { onLog("    [invoke] connect arg[0]=${raw0?.javaClass?.name}") }
                                val clientBinder = raw0 as IBinder
                                val flags = args[1] as Int
                                runCatching { onLog("    [invoke] connect flags=0x${Integer.toHexString(flags)}") }
                                proxyHelper.handleConnect(clientBinder, flags)
                                runCatching { onLog("    [invoke] handleConnect OK") }
                                null
                            } catch (t: Throwable) {
                                Log.e(TAG, "  connect: invoke 抛出给 framework", t)
                                runCatching { onLog("    [invoke] connect THROW: ${t.javaClass.name}: ${t.message}") }
                                throw t
                            }
                        }
                        "disconnect" -> {
                            try {
                                proxyHelper.handleDisconnect()
                                null
                            } catch (t: Throwable) {
                                Log.e(TAG, "  disconnect: invoke 抛出", t)
                                runCatching { onLog("    [invoke] disconnect THROW: ${t.javaClass.name}: ${t.message}") }
                                throw t
                            }
                        }
                        "equals" -> proxy === args!![0]
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" ->
                            "IUiAutomationConnection(dynamicProxy)@" +
                                    Integer.toHexString(System.identityHashCode(proxy))
                        else -> {
                            Log.w(TAG, "Unexpected interface method: ${method}")
                            runCatching { onLog("    [invoke] UNEXPECTED method=${method.name}") }
                            null
                        }
                    }
                }
            )

            val ctor = UiAutomation::class.java.getConstructor(
                Looper::class.java,
                iConnClass
            )
            val ua = ctor.newInstance(handlerThread.looper, proxyAny) as UiAutomation

            // Android 12+ 有 connect(int flags)，老版本只有 connect() 无参。
            val connectMethod = try {
                UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                UiAutomation::class.java.getMethod("connect")
            }
            if (connectMethod.parameterCount == 1) {
                connectMethod.invoke(ua, flagDontSuppress)
            } else {
                connectMethod.invoke(ua)
            }

            mUiAutomation = ua

            // **不要**再调 setServiceInfo：GKD 不调，强行调反而可能在我们 capability 缺失等情况下
            // 把已连好的 service 弄成不一致状态。连接一次性建好就够了。

            onLog("[3/3] 连接成功 ✅（UiAutomation 已就绪，可用 getRoot/performGlobalAction）")
            true
        } catch (e: Throwable) {
            // 只解包 InvocationTargetException（反射 invoke 的壳）。注意：**不要**解包 RuntimeException——
            // IllegalStateException / SecurityException 等真实异常都继承 RuntimeException，继续解包会一路解到
            // cause 链里的 RemoteException("Remote stack trace")，反而丢失真实原因。
            // handleConnect 抛的 RuntimeException 的 message 已含真实信息（如 "registerUiTestAutomationService 失败: already registered"）。
            var root: Throwable = e
            var guard = 0
            while (guard++ < 10 && root is java.lang.reflect.InvocationTargetException && root.cause != null) {
                root = root.cause!!
            }
            val isAlreadyRegistered = root.message?.contains("already registered", ignoreCase = true) == true
            // already registered：system_server 残留旧注册（上次 disconnect 未及时注销）。
            // 先清理后延迟重试一次，等旧 client binder 被 GC、system_server 自动注销。
            if (attempt < 1 && isAlreadyRegistered) {
                onLog("检测到 UiAutomationService 残留注册，清理后重试一次…")
                cleanup()
                SystemClock.sleep(500)
                return connectRetry(context, timeoutMs, onLog, attempt + 1)
            }
            // 最终失败：残留注册重试后仍失败（App 进程被杀、旧 client binder 拿不到），只能重启 Shizuku
            lastError = if (isAlreadyRegistered) {
                "可能有其它应用占用了该模式，请关闭其它应用的该模式，还是不行的话， 再重启 Shizuku（或重启手机）后重试"
            } else {
                "${root.javaClass.simpleName}: ${root.message}"
            }
            onLog("✗ 连接异常: $lastError")
            root.printStackTrace()
            cleanup()
            false
        }
    }

    /**
     * 当前窗口根节点（AccessibilityNodeInfo），走 UiAutomation 公开 API。
     *
     * 多级兜底（GKD 风格）：
     *  1) `rootInActiveWindow` —— 常规路径；
     *  2) 若为空，退回从 `getWindows()` 取「活跃窗口」的 root，再退回首个窗口的 root。
     *     shell 身份注册的 UiAutomation 在较新 Android / 部分 ROM 上 `rootInActiveWindow`
     *     会返回 null，但 `getWindows()` 往往仍能拿到窗口与 root，此兜底可覆盖。
     *
     * 同时打印诊断：serviceInfo.flags（系统实际采纳的 flags，确认 RETRIEVE_WINDOW_CONTENT
     * 是否生效）、窗口数、每窗口 active/root 状态。logcat tag=InvisibleAuto。
     */
    fun getRoot(): AccessibilityNodeInfo? {
        val ua = mUiAutomation ?: run {
            diag("getRoot: mUiAutomation=null（未连接？）", Log.WARN)
            return null
        }
        return try {
            val direct = ua.rootInActiveWindow
            if (direct != null) {
                diag("getRoot: rootInActiveWindow 直接命中 ✅")
                return direct
            }
            diag("getRoot: rootInActiveWindow=null，开始查 windows...")
            val regFlags = mProxyHelper?.registeredFlags ?: 0
            val regConnFlags = mProxyHelper?.registeredConnectFlags ?: -1
            val regUserId = mProxyHelper?.registeredUserId ?: -1
            val svcFlags = runCatching { ua.serviceInfo?.flags ?: 0 }.getOrNull() ?: 0
            diag(
                "getRoot: 注册info.flags=0x${Integer.toHexString(regFlags)} | " +
                        "注册connectFlags=0x${Integer.toHexString(regConnFlags)} " +
                        "(ALLOW_MONITORED? ${regConnFlags and 0x4 != 0}) | " +
                        "注册userId=$regUserId | " +
                        "系统回报flags=0x${Integer.toHexString(svcFlags)} " +
                        "(RETRIEVE_WINDOW_CONTENT? ${svcFlags and 0x1 != 0})"
            )

            var wins: List<AccessibilityWindowInfo>? =
                runCatching { ua.windows }.getOrElse {
                    diag(
                        "getRoot: ua.windows 抛异常: ${it.javaClass.simpleName}: ${it.message}",
                        Log.WARN
                    )
                    null
                }
            diag("getRoot: wins=${describeWins(wins)}")

            // windows 为空 → 延迟 200ms 再试一次，排查初始化时序
            if (wins.isNullOrEmpty()) {
                diag("getRoot: windows 为空，等待 200ms 后重试...")
                runCatching { Thread.sleep(200) }
                wins = runCatching { ua.windows }.getOrElse { null }
                diag("getRoot: 重试后 wins=${describeWins(wins)}")
            }

            // 仍为空 → 兜底保险：标准 framework 在 init 回调里会自动调
            // AccessibilityInteractionClient.addConnection 填充连接缓存；若个别 ROM 未自动填充
            // （表现为 init 已回调但 wins 仍空），这里反射取出 mConnectionId 与 mClient.mConnection
            // 自行补一次。幂等、版本无关、无需写 AIDL。正常路径（framework 自动 addConnection）
            // 下本分支不会命中。
            if (wins.isNullOrEmpty()) {
                val patched = ensureConnectionCache(ua)
                if (patched) {
                    diag("getRoot: 已补 addConnection，重试 windows...")
                    wins = runCatching { ua.windows }.getOrElse { null }
                    diag("getRoot: 补缓存后 wins=${describeWins(wins)}")
                    // addConnection 后 rootInActiveWindow 往往也能用了
                    val reRoot = runCatching { ua.rootInActiveWindow }.getOrNull()
                    if (reRoot != null) {
                        diag("getRoot: addConnection 后 rootInActiveWindow 命中 ✅")
                        return reRoot
                    }
                }
            }

            wins?.forEachIndexed { i, w ->
                val r = runCatching { w.root }.getOrNull()
                diag("  window[$i] isActive=${w.isActive} hasRoot=${r != null}")
            }
            val fromWin = wins?.firstOrNull { it.isActive }?.root
                ?: wins?.firstOrNull()?.root
            if (fromWin != null) diag("getRoot: 从 windows 命中 ✅")
            else diag("getRoot: windows 也无可用 root ❌", Log.WARN)
            fromWin
        } catch (e: Throwable) {
            diag("getRoot 异常: ${e.javaClass.name}: ${e.message}", Log.WARN)
            null
        }
    }

    private fun describeWins(wins: List<AccessibilityWindowInfo>?): String = when {
        wins == null -> "null(异常)"
        wins.isEmpty() -> "空(0 个)"
        else -> "size=${wins.size}"
    }

    /**
     * 兜底保险：标准 framework 在 system_server 回调 init() 时会自动调用
     * AccessibilityInteractionClient.addConnection(connectionId, connection, true) 填充连接缓存；
     * 本库 connection 已通过 Binder.onTransact 正确路由到 handleConnect（对齐 GKD），init 会被
     * 正常回调，正常路径下 framework 自动 addConnection，本方法不会被触发。仅当个别 ROM 未自动
     * 填充（init 已回调但 wins 仍空）时，反射取出 mConnectionId 与 mClient.mConnection 自行补一次。
     * 幂等（已填充则覆盖为同一对象，无害）、版本无关、不需要写 AIDL。
     *
     * @return true 表示已成功补缓存；false 表示无法补齐（会打印诊断，便于真机排查）。
     */
    private fun ensureConnectionCache(ua: UiAutomation): Boolean {
        return runCatching {
            // 1) mConnectionId（int，UiAutomation 实例上，init 后由 system_server 回调写入）
            val connId = readIntField(ua, listOf("mConnectionId", "mConnectionState"))
            if (connId <= 0) {
                diag("ensureConnectionCache: mConnectionId=$connId（init 未回调？），跳过", Log.WARN)
                return@runCatching false
            }
            // 2) mClient（IAccessibilityServiceClientImpl，UiAutomation 私有内部类实例）
            val client = readFieldDeep(ua, listOf("mClient")) ?: run {
                diag("ensureConnectionCache: mClient 未取到（字段名可能变动）", Log.WARN)
                return@runCatching false
            }
            // 3) mClient.mConnection（IAccessibilityServiceConnection，init 回调时写入）
            val connection = readFieldDeep(client, listOf("mConnection")) ?: run {
                diag("ensureConnectionCache: mClient.mConnection 未取到（init 未回调？）", Log.WARN)
                return@runCatching false
            }
            // 4) AccessibilityInteractionClient.getInstance().addConnection(connId, connection, true)
            val aicClass = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
            val aic = aicClass.getMethod("getInstance").invoke(null)
            val iConnClass =
                Class.forName("android.accessibilityservice.IAccessibilityServiceConnection")
            val addConn = try {
                aicClass.getMethod(
                    "addConnection",
                    Int::class.javaPrimitiveType,
                    iConnClass,
                    Boolean::class.javaPrimitiveType
                )
            } catch (_: NoSuchMethodException) {
                // 极少数旧版本只有 2 参数签名
                aicClass.getMethod("addConnection", Int::class.javaPrimitiveType, iConnClass)
            }
            if (addConn.parameterCount == 3) {
                addConn.invoke(aic, connId, connection, true)
            } else {
                addConn.invoke(aic, connId, connection)
            }
            diag("ensureConnectionCache: 已 addConnection(connId=$connId) ✅")
            true
        }.onFailure {
            diag("ensureConnectionCache 失败: ${it.javaClass.simpleName}: ${it.message}", Log.WARN)
        }.getOrElse { false }
    }

    /** 读取 int 字段（按候选名尝试），失败返回 -1。 */
    private fun readIntField(obj: Any, names: List<String>): Int {
        for (n in names) {
            runCatching {
                val f = obj.javaClass.getDeclaredField(n).apply { isAccessible = true }
                return f.getInt(obj)
            }
        }
        return -1
    }

    /** 读取对象字段（按候选名、并向上遍历类继承链尝试），失败返回 null。 */
    private fun readFieldDeep(obj: Any, names: List<String>): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (n in names) {
                runCatching {
                    val f = clazz!!.getDeclaredField(n).apply { isAccessible = true }
                    return f.get(obj)
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    /** 诊断日志：同时写 logcat 与 App 日志面板（uiLog），免去 Windows 上 adb/grep 的麻烦。 */
    private fun diag(msg: String, level: Int = Log.INFO) {
        Log.println(level, TAG, msg)
        runCatching { uiLog?.invoke("[root] $msg") }
    }

    /** 当前所有窗口（AccessibilityWindowInfo）。 */
    fun getWindows(): List<AccessibilityWindowInfo>? = try {
        mUiAutomation?.windows
    } catch (e: Throwable) {
        Log.w(TAG, "getWindows 异常", e); null
    }

    /** 全局动作：返回 / 桌面 / 最近任务 等（AccessibilityService.GLOBAL_ACTION_*）。 */
    fun performGlobalAction(action: Int): Boolean = try {
        mUiAutomation?.performGlobalAction(action) ?: false
    } catch (e: Throwable) {
        Log.w(TAG, "performGlobalAction($action) 失败", e); false
    }

    fun clickNode(node: AccessibilityNodeInfo?): Boolean =
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

    fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = getRoot() ?: return null
        return dfs(root, predicate)
    }

    fun clickByText(text: String, contains: Boolean = true): Boolean {
        val target = findNode { n ->
            val t = n.text?.toString().orEmpty()
            if (contains) t.contains(text) else t == text
        }
        return clickNode(target)
    }

    fun clickById(viewId: String): Boolean {
        val target = findNode { n -> n.viewIdResourceName == viewId }
        return clickNode(target)
    }

    // ===================== 补全能力（对齐 AccessibilityService 常用 API）=====================

    /** 焦点类型常量，等价于 AccessibilityNodeInfo.FOCUS_*（与 AccessibilityService 同值）。 */
    val FOCUS_INPUT: Int get() = uiAutoConst("FOCUS_INPUT", 1)
    val FOCUS_ACCESSIBILITY: Int get() = uiAutoConst("FOCUS_ACCESSIBILITY", 2)

    /** 节点动作常量，等价于 AccessibilityNodeInfo.ACTION_*（公开常量，直接引用）。 */
    val ACTION_CLICK: Int get() = AccessibilityNodeInfo.ACTION_CLICK
    val ACTION_LONG_CLICK: Int get() = AccessibilityNodeInfo.ACTION_LONG_CLICK
    val ACTION_SCROLL_FORWARD: Int get() = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    val ACTION_SCROLL_BACKWARD: Int get() = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    val ACTION_SET_TEXT: Int get() = AccessibilityNodeInfo.ACTION_SET_TEXT
    val ACTION_FOCUS: Int get() = AccessibilityNodeInfo.ACTION_FOCUS

    /**
     * 按焦点类型取当前焦点节点（等价于 AccessibilityService.findFocus）。
     * focusType 用 [FOCUS_INPUT] / [FOCUS_ACCESSIBILITY]。
     */
    fun findFocus(focusType: Int): AccessibilityNodeInfo? = try {
        mUiAutomation?.findFocus(focusType)
    } catch (e: Throwable) {
        Log.w(TAG, "findFocus($focusType) 异常", e); null
    }

    /**
     * 对节点执行任意 AccessibilityNodeInfo 动作，覆盖 ACTION_CLICK / ACTION_LONG_CLICK /
     * ACTION_SCROLL_FORWARD / ACTION_SET_TEXT 等（用上面常量或 AccessibilityNodeInfo.ACTION_*）。
     * 例：设置文本 →
     *   performAction(node, ACTION_SET_TEXT,
     *     Bundle().apply { putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE", "hello") })
     */
    fun performAction(
        node: AccessibilityNodeInfo?,
        action: Int,
        args: Bundle? = null
    ): Boolean = node?.performAction(action, args) ?: false

    /** 收集所有满足条件的节点（等价于多次 findAccessibilityNodeInfosBy*）。 */
    fun findAll(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = getRoot() ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        dfsCollect(root, predicate, out)
        return out
    }

    /**
     * 注册无障碍事件监听（节点/窗口变化等），等价于 AccessibilityService 的 onAccessibilityEvent。
     * 回调发生在 UiAutomation 所在线程（InvisibleAutoThread），要更新 UI 请自行 post 到主线程。
     * 传入 null 取消监听。
     */
    fun setOnAccessibilityEventListener(
        listener: ((AccessibilityEvent) -> Unit)?
    ) {
        val ua = mUiAutomation ?: return
        if (listener == null) {
            ua.setOnAccessibilityEventListener(null)
            return
        }
        ua.setOnAccessibilityEventListener { event -> listener(event) }
    }

    fun clearAccessibilityEventListener() = setOnAccessibilityEventListener(null)

    /**
     * 注入一段手势（按下→移动序列→抬起）。
     *
     * 实现对齐 GKD `CompatInputManager`（Android 12+ 分支）：走 shell `input tap` / `input swipe`
     * （shell 身份，最可靠，无需 INJECT_EVENTS）。之前用 `UiAutomation.injectInputEvent` 反射 +
     * 简化 `MotionEvent.obtain(downTime,eventTime,action,x,y,0)` 注入裸事件，但该重载 source=0、
     * 无 pressure/deviceId，被 InputDispatcher 直接丢弃——表现为「轨迹画对但点击无效果」。
     * 故改为：单点/首尾近乎重合视为点击走 `input tap`，否则走 `input swipe`（首尾两点 + 时长）。
     * 缺点：不再保留拟人化的多段微移轨迹（shell 命令无法逐点注入），与 GKD 一致、接受该取舍。
     *
     * points 为屏幕坐标轨迹；durationMs 为整段手势时长；单点等价于点击。失败时返回 false。
     */
    fun dispatchGesture(points: List<Point>, durationMs: Long = 300): Boolean {
        if (points.isEmpty()) return false
        val first = points.first()
        val last = points.last()
        val isClick = points.size == 1 ||
                (kotlin.math.abs(first.x - last.x) < 5 && kotlin.math.abs(first.y - last.y) < 5)
        return if (isClick) {
            tap(first.x, first.y)
        } else {
            // shell `input swipe` 匀速、抬起无 fling 惯性；无障碍 dispatchGesture 有加速度+fling。
            // 锁屏上划解锁需快速 fling 才识别为「解锁」，故减半时长提高松手速度（终点不变）。
            val flingMs = (durationMs / 2).coerceIn(600L, 1000L).toInt()
            SystemClock.sleep(1000L)
            swipe(first.x, first.y, last.x, last.y, flingMs)
        }
    }

    private fun dfsCollect(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (predicate(node)) out.add(node)
        for (i in 0 until node.childCount) {
            dfsCollect(node.getChild(i), predicate, out)
        }
    }

    /** 坐标点击：经 shell `input tap`（shell 权限，无需 INJECT_EVENTS）。 */
    fun tap(x: Int, y: Int): Boolean {
        val r = mSvc?.exec("input tap $x $y") ?: return false
        val ok = r.exitCode == 0
        diag("[tap] input tap $x $y → exit=${r.exitCode} ${
            if (ok) "" else "stdout=${r.stdout.take(200)} stderr=${r.stderr.take(200)}"
        }")
        return ok
    }

    /** 坐标滑动：经 shell `input swipe`。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean {
        val r = mSvc?.exec("input swipe $x1 $y1 $x2 $y2 $durationMs") ?: return false
        val ok = r.exitCode == 0
        diag("[swipe] input swipe $x1 $y1 $x2 $y2 $durationMs → exit=${r.exitCode} ${
            if (ok) "" else "stdout=${r.stdout.take(200)} stderr=${r.stderr.take(200)}"
        }")
        return ok
    }

    fun exec(command: String): com.google.android.accessibility.uiautomation.shizuku.ShellResult? =
        mSvc?.exec(command)

    /**
     * 探测 system_server 是否已有 UiAutomation 注册（被其它 App/进程占用）。
     * 借 shell `dumpsys accessibility` 输出判断：UiAutomationManager 仅在已注册时才会 dump
     * 出 `Ui Automation[...]` 这行。只能判断"是否被占用"，无法得知占用者是谁。
     * dumpsys 失败/未注册时均返回 false（保守）。
     */
    fun isUiAutomationOccupied(): Boolean {
        return try {
            val r = mSvc?.exec("dumpsys accessibility | grep 'Ui Automation'") ?: return false
            !r.stdout.isNullOrBlank()
        } catch (_: Throwable) {
            false
        }
    }

    fun disconnect() = cleanup()

    private fun cleanup() {
        try {
            mUiAutomation?.let { ua ->
                val m = try {
                    UiAutomation::class.java.getMethod("disconnect")
                } catch (_: NoSuchMethodException) {
                    UiAutomation::class.java.getDeclaredMethod("disconnect").apply {
                        isAccessible = true
                    }
                }
                m.invoke(ua)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cleanup: ua.disconnect 异常", t)
        }
        try {
            mProxyHelper?.handleDisconnect()
        } catch (_: Throwable) {
        }
        runCatching { mHandlerThread?.quit() }
        mUiAutomation = null
        mProxyHelper = null
        mHandlerThread = null
        // 不 unbind Shizuku UserService：shell 通道（input tap/swipe、wm size）本就可复用。
        // 若在此 unbind（removeTask=true 会杀 UserService 进程），下次 bind() 需重新 fork 进程、
        // 易超时返回 null（用户反馈「绑定成功后再次绑定提示超时」）。仅清本地引用，保留
        // AutomationShizuku.userService 供下次 bind() 直接复用。
        mSvc = null
    }

    private fun dfs(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = dfs(child, predicate)
            if (found != null) return found
        }
        return null
    }
}
