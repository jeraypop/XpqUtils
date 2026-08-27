package com.google.android.accessibility.uiautomation.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 隐藏 API 桥接：所有 @hide 的调用都走这里，统一用 HiddenApiBypass 豁免。
 * 既在 App 进程使用（无需 UiAutomation 的反射链路），也在 shell UserService 进程使用
 * （registerUiTestAutomationService）。
 */
object HiddenApi {

    private const val TAG = "HiddenApi"

    @Volatile
    private var initialized = false

    /** UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，AOSP 中固定为 1 */
    const val FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES: Int = 1

    /**
     * AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT = 0x1。**关键**：
     * system_server 端 `canRetrieveWindowsLocked`/`canRetrieveWindowContentLocked` 实际检查的是
     * `service.getCapabilities() & CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT`（AOSP 14
     * `AccessibilitySecurityPolicy.canRetrieveWindowContentLocked`），而不是 `info.flags`！
     * 只设 FLAG_RETRIEVE_WINDOW_CONTENT 但不设 capability，system_server 端 getWindows()/getRoot()
     * 会直接返回 null，wins 永远=0。这是与 GKD 关键差异：GKD 用 `toHidden.setCapabilities(rawInfo.capabilities)`
     * 把自家 a11y 服务的 capability 拷过来；我们一直没设 capability，所以 w 永远取不到。
     * 反射取值，回退 0x1。
     */
    val CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT: Int by lazy {
        runCatching {
            AccessibilityServiceInfo::class.java
                .getField("CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT").getInt(null)
        }.getOrElse { 0x1 }
    }

    /**
     * AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES = 0x20。GKD 也拷过去，
     * 便于后续手势/输入事件 API。
     */
    val CAPABILITY_CAN_PERFORM_GESTURES: Int by lazy {
        runCatching {
            AccessibilityServiceInfo::class.java
                .getField("CAPABILITY_CAN_PERFORM_GESTURES").getInt(null)
        }.getOrElse { 0x20 }
    }

    /**
     * AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT = 0x80。GKD 也带。
     */
    val CAPABILITY_CAN_TAKE_SCREENSHOT: Int by lazy {
        runCatching {
            AccessibilityServiceInfo::class.java
                .getField("CAPABILITY_CAN_TAKE_SCREENSHOT").getInt(null)
        }.getOrElse { 0x80 }
    }

    /**
     * AccessibilityServiceInfo.FLAG_FORCE_DIRECT_BOOT_AWARE = 0x00010000。GKD
     * `UiAutomationServiceInfo` 用 `rawInfo.flags or FLAG_FORCE_DIRECT_BOOT_AWARE` 强制
     * Direct Boot 感知；缺这个 flag 可能在 user 还没解锁（CE 加密但 DE 已解锁）时拒注册/取不到窗口。
     */
    val FLAG_FORCE_DIRECT_BOOT_AWARE: Int by lazy {
        runCatching {
            AccessibilityServiceInfo::class.java
                .getField("FLAG_FORCE_DIRECT_BOOT_AWARE").getInt(null)
        }.getOrElse { 0x00010000 }
    }

    /**
     * UiAutomation.FLAG_ALLOW_MONITORED_IMMEDIATE_TREE_OBSERVATION。
     *
     * registerUiTestAutomationService 的 flags 参数里**必须**包含这个 bit（值随版本变化，
     * Android 14+ 通常为 0x4），否则 system_server 不会把这个 client 加入窗口观察列表，
     * 导致 `ua.windows()` 恒为空，进而 `rootInActiveWindow()` 也为 null。
     * 仅 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES = 0x1` 不足以触发观察。
     *
     * 反射取值，回退 0x4。
     */
    val FLAG_ALLOW_MONITORED_IMMEDIATE_TREE_OBSERVATION: Int by lazy {
        runCatching {
            UiAutomation::class.java
                .getField("FLAG_ALLOW_MONITORED_IMMEDIATE_TREE_OBSERVATION").getInt(null)
        }.getOrElse { 0x4 }
    }

    /**
     * AccessibilityServiceInfo.FLAG_IS_AUTOMATION_TOOL，注册 UiTestAutomationService 时
     * 必须在 info.flags 带上，否则部分 ROM 拒绝注册 / 节点查询与全局动作被静默拒绝。
     *
     * 该常量值随 Android 版本变化（Android 12 = 0x80，Android 14 = 0x80，旧部分 ROM = 0x40），
     * 不能硬编码。运行时反射读取 framework 的 AccessibilityServiceInfo 常量。
     */
    val FLAG_IS_AUTOMATION_TOOL: Int by lazy {
        runCatching {
            AccessibilityServiceInfo::class.java
                .getField("FLAG_IS_AUTOMATION_TOOL").getInt(null)
        }.getOrElse { 0x80 }
    }

    /**
     * AccessibilityServiceInfo.FLAG_RETRIEVE_WINDOW_CONTENT，让 UiAutomation 通道的
     * rootInActiveWindow 能取到整棵节点树。compileSdk 36 的 android.jar 桩未暴露该字段
     * （直接引用会 Unresolved reference），故运行时反射取值；其 AOSP 值自 API 16 起稳定为 0x1。
     */
    val FLAG_RETRIEVE_WINDOW_CONTENT: Int by lazy {
        runCatching {
            AccessibilityServiceInfo::class.java
                .getField("FLAG_RETRIEVE_WINDOW_CONTENT").getInt(null)
        }.getOrElse { 0x1 }
    }

    fun ensure() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                // 豁免全部非 SDK 接口（本库明确需要调用隐藏 API）
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (_: Throwable) {
                // 部分 ROM / 低版本可能不支持，忽略
            }
            initialized = true
        }
    }

    /** 获取系统 IAccessibilityManager（隐藏接口，普通 app 身份）。 */
    fun getIAccessibilityManager(): Any {
        ensure()
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java)
            .invoke(null, "accessibility") as IBinder
        val stub = Class.forName("android.app.IAccessibilityManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /**
     * 把原始 AccessibilityManager binder 包成 IAccessibilityManager 代理。
     *
     * 传入的 [binder] 通常是 `ShizukuBinderWrapper(SystemServiceHelper.getSystemService("accessibility"))`，
     * 这样后续所有 transact 都经 Shizuku 以 shell 身份（uid 2000）发出 —— 与 GKD 的
     * `PrivilegeBinderWrapper.fromSystemService` 等价。
     *
     * 兼容不同 ROM 的 Stub 包名（android.view.accessibility / android.app）。
     */
    fun asInterfaceIAccessibilityManager(binder: IBinder): Any {
        ensure()
        val candidates = listOf(
            "android.view.accessibility.IAccessibilityManager\$Stub",
            "android.app.IAccessibilityManager\$Stub",
        )
        for (name in candidates) {
            runCatching {
                val stub = Class.forName(name)
                return stub.getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)!!
            }
        }
        throw ClassNotFoundException("IAccessibilityManager\$Stub 在两个候选包名中均未找到")
    }

    /**
     * 反射调 `registerUiTestAutomationService`，自动适配各 Android 版本的参数列表。
     *
     * AOSP 不同版本 `IAccessibilityManager.registerUiTestAutomationService` 的签名：
     *  - Android 12 (S, API 31-32)：
     *    `boolean(IBinder owner, IAccessibilityServiceClient client, AccessibilityServiceInfo info, int userId, int flags)`
     *  - Android 13 (T, API 33+)：
     *    `boolean(IAccessibilityServiceClient client, AccessibilityServiceInfo info, int userId, int flags)`
     *  - 还有更早（仅 client + info），甚至加 pid/uid 的扩展变体。
     *
     * 策略：按方法名 + 第一参数名 / 类型探测，按参数位置填充值。
     *
     * @return 注册是否被 system_server 接受（仅在某些版本该方法会返回 boolean，否则恒为 true）
     */
    fun registerUiTestAutomationService(
        iam: Any,
        owner: IBinder,
        client: Any,                         // 运行期类型为 IAccessibilityServiceClient（Proxy 或 Stub）
        info: AccessibilityServiceInfo,
        flags: Int,
        userId: Int = 0
    ): Boolean {
        ensure()
        val clientInterface = Class.forName("android.accessibilityservice.IAccessibilityServiceClient")
        val ibinderClass = IBinder::class.java
        val infoClass = AccessibilityServiceInfo::class.java
        val intPrim = Int::class.javaPrimitiveType

        val methods = iam.javaClass.methods.filter { it.name == "registerUiTestAutomationService" }
        if (methods.isEmpty()) {
            Log.e(TAG, "找不到 registerUiTestAutomationService 方法（IAccessibilityManager 接口中没有同名方法）")
            return false
        }
        // 找出参数最像的：优先以 IAccessibilityServiceClient 所在位置匹配
        val method = methods.maxByOrNull { scoreCandidate(it, clientInterface) }
            ?: methods.first()
        Log.i(TAG, "registerUiTestAutomationService 候选数=${methods.size}, 选中=${
            describeMethod(method)
        }")

        val paramTypes = method.parameterTypes
        val args = arrayOfNulls<Any>(paramTypes.size)
        // 先把所有 int 位置收集（按出现顺序）
        val intIdx = paramTypes.mapIndexedNotNull { i, t ->
            if (t == intPrim || t == Int::class.java) i else null
        }
        // 最后一个 int 是 flags；倒数第二个若为 Int 当 userId
        val flagsIdx = intIdx.lastOrNull() ?: -1
        val userIdIdx = if (intIdx.size >= 2) intIdx[intIdx.size - 2] else -1

        for (i in paramTypes.indices) {
            val t = paramTypes[i]
            args[i] = when {
                t == ibinderClass || t.name == "android.os.IBinder" -> owner
                t.isAssignableFrom(clientInterface) || t == clientInterface -> client
                t == infoClass -> info
                t == intPrim || t == Int::class.java -> {
                    when (i) {
                        userIdIdx -> userId
                        flagsIdx -> flags
                        else -> -1  // 其它 int（pid/uid 等）；本流程无需关心，给 0 即可
                    }
                }
                else -> null
            }
        }
        Log.i(
            TAG,
            "registerUiTestAutomationService 实参: owner=$owner client=${
                client.javaClass.simpleName
            } info.flags=0x${
                java.lang.Integer.toHexString(info.flags)
            } userId=$userId flags=0x${java.lang.Integer.toHexString(flags)}"
        )
        val ret = try {
            method.invoke(iam, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 解包：system_server 拒绝注册的真实异常藏在 InvocationTargetException.cause 里，
            // 直接抛 cause 让上层（handleConnect）拿到明确原因（如 "already registered" / SecurityException）
            val cause = e.cause ?: e
            Log.e(TAG, "registerUiTestAutomationService 被 system_server 拒绝: ${cause.javaClass.name}: ${cause.message}", cause)
            throw cause
        }
        return when (ret) {
            is Boolean -> ret
            else -> true
        }
    }

    private fun scoreCandidate(m: java.lang.reflect.Method, clientInterface: Class<*>): Int {
        var s = 0
        // 含 clientInterface 类型（必需）→ 高权重
        if (m.parameterTypes.any { it == clientInterface }) s += 100
        // 含 AccessibilityServiceInfo（必需）
        if (m.parameterTypes.any { it == AccessibilityServiceInfo::class.java }) s += 50
        // 含 IBinder owner（Android 12+ 风格）→ 轻微加分（同版本若存在多个候选时优先带 owner 的）
        if (m.parameterTypes.any { it == IBinder::class.java }) s += 5
        // 参数个数偏好：5 参（owner+client+info+userId+flags）略优于 4 参
        s += when (m.parameterCount) {
            5 -> 2
            4 -> 1
            else -> 0
        }
        return s
    }

    private fun describeMethod(m: java.lang.reflect.Method): String =
        m.parameterTypes.joinToString(", ", prefix = "(", postfix = ")") { it.simpleName }

    /** 反射调 `unregisterUiTestAutomationService`，适配参数变化（部分版本还要 IBinder owner）。 */
    fun unregisterUiTestAutomationService(iam: Any, client: Any): Boolean {
        ensure()
        val clientInterface = Class.forName("android.accessibilityservice.IAccessibilityServiceClient")
        val methods = iam.javaClass.methods.filter { it.name == "unregisterUiTestAutomationService" }
        val m = methods.firstOrNull {
            it.parameterTypes.any { t -> t == clientInterface || t.isAssignableFrom(clientInterface) }
        } ?: return false
        val arg = when (val first = m.parameterTypes[0]) {
            clientInterface, IBinder::class.java -> when (first) {
                IBinder::class.java -> (client as? IBinder) ?: run {
                    val b = client.javaClass.getMethod("asBinder").invoke(client) as IBinder
                    b
                }
                else -> client
            }
            else -> client
        }
        val ret = m.invoke(iam, arg)
        return when (ret) {
            is Boolean -> ret
            else -> true
        }
    }

    /** 当前用户 id（UserHandle.myUserId，部分版本隐藏）。 */
    fun currentUserId(): Int {
        ensure()
        return try {
            val uh = Class.forName("android.os.UserHandle")
            uh.getMethod("myUserId").invoke(null) as Int
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * 通过反射把 capabilities 写到 AccessibilityServiceInfo。
     *
     * 关键：system_server 端 `canRetrieveWindowsLocked` 实际看 `service.getCapabilities() &
     * CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT`（AOSP 14 AccessibilitySecurityPolicy），与 info.flags
     * **无关**。所以仅设 flags 没有用，必须调 setCapabilities。
     *
     * 调用失败时仅警告（不抛），便于真机排查。
     */
    fun setCapabilities(info: AccessibilityServiceInfo, capabilities: Int) {
        ensure()
        try {
            val m = AccessibilityServiceInfo::class.java.getMethod("setCapabilities", Int::class.javaPrimitiveType)
            m.invoke(info, capabilities)
        } catch (t: Throwable) {
            Log.w(TAG, "setCapabilities(0x${Integer.toHexString(capabilities)}) 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 通过反射调 `setAccessibilityTool(true)`（Android 14+ 强烈推荐，标志此 service 是无障碍工具；
     * GKD 在 Android 14+ 显式调，部分 ROM 缺这个标志会拒绝为工具类 UiAutomation 返回窗口）。
     */
    fun setAccessibilityTool(info: AccessibilityServiceInfo, value: Boolean) {
        ensure()
        try {
            val m = AccessibilityServiceInfo::class.java.getMethod("setAccessibilityTool", Boolean::class.javaPrimitiveType)
            m.invoke(info, value)
        } catch (t: Throwable) {
            Log.w(TAG, "setAccessibilityTool($value) 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 兼容旧实现：反射构造 `UiAutomation(Looper, IUiAutomationConnection)`。
     * 当前 InvisibleAutomation 已不走 UiAutomation 类，本方法保留用于自定义调用方。
     */
    @Suppress("UNUSED_PARAMETER")
    fun newUiAutomation(looper: Looper, connection: Any): UiAutomation {
        ensure()
        val uaClass = UiAutomation::class.java
        val connClass = Class.forName("android.app.IUiAutomationConnection")
        val ctor = uaClass.getDeclaredConstructor(Looper::class.java, connClass)
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(looper, connection) as UiAutomation
    }

    /** 当前进程的 pid（公开 API，但统一在这个文件暴露便于阅读）。 */
    fun myPid(): Int = Process.myPid()
}
