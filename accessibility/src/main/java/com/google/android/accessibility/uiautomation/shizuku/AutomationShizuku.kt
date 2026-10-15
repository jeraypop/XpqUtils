package com.google.android.accessibility.uiautomation.shizuku

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku 接入封装（对齐 acclib.ShizukuCore 的绑定写法）。
 *
 * 负责：
 *  - 检测 Shizuku 是否已安装 / 已授权；
 *  - 申请 Shizuku 权限；
 *  - 以 shell 身份绑定 AutomationUserService（持久化，供自动化引擎重复使用）。
 */
object AutomationShizuku {

    const val PERMISSION_CODE = 1024

    /** Shizuku 管理器主界面类名（各 fork 通常只改 applicationId，不改这个类名）。 */
    private const val MANAGER_MAIN_ACTIVITY = "moe.shizuku.manager.MainActivity"

    /**
     * 用于**零配置反查「管理器所在应用」包名**的固定 action。
     *
     * manager（官方 Shizuku App 与 `com.github.jeraypop.Shizuku:manager` 库）的清单里恒有这两个
     * 组件声明 filter，且 action 是**字面量**（不像别的 action 带 `${shizukuApplicationId}` 占位符）：
     *  - `moe.shizuku.manager.legacy.ShellRequestHandlerActivity`（`exported="true"`，纯 DEFAULT 分类）
     *  - `moe.shizuku.manager.receiver.BinderRequestReceiver`（`exported="true"` 广播接收器）
     *
     * 所以「谁声明了它，谁就是管理器所在应用」—— 这是唯一不需要宿主写代码就能拿到管理器包名的办法
     * （主界面 `MainActivity` 自己 `exported` 却**没有任何 intent-filter**，见 [ManagerApp]）。
     * 可见性前提：本库 manifest 的 `<queries>` 里声明了同一 action，Android 11+ 才看得见它。
     */
    private const val MANAGER_PROBE_ACTION = "rikka.shizuku.intent.action.REQUEST_BINDER"

    /** [discoverManagerPackage] 的探测结果缓存（管理器不会中途换包名，命中即长期有效）。 */
    @Volatile
    private var discoveredManagerPackage: String? = null

    /** 官方/常见独立 Shizuku App 包名，兜底用。 */
    private val KNOWN_SHIZUKU_PACKAGES = arrayOf("moe.shizuku.privileged.api", "rikka.shizuku")

    /**
     * 「Shizuku 管理器所在应用」= 包名 + 主界面类名，用于**按组件名直跳**。
     *
     * 为什么必须有这条路（不能只靠扫描已安装应用）：fork 的 manager 以库形式并入某个宿主时，
     * manager AAR 清单里的 `moe.shizuku.manager.MainActivity` 是 `exported="true"` 但
     * **没有任何 intent-filter**（原文如此，见 `com.github.jeraypop.Shizuku:manager` 的 AAR 清单）→
     * `queryIntentActivities(CATEGORY_LAUNCHER)` 永远扫不到它；若那个宿主自己也没有启动页，
     * `getLaunchIntentForPackage` 同样返回 null。所以**跳转只能靠「已知类名 + 显式组件」**；
     * 包名平时由 [MANAGER_PROBE_ACTION] 自动反查得到，本类只给「连那个固定 action 都被 fork 改掉」
     * 的极端情形留一个手工入口（[addStandaloneShizukuPackage]）。
     *
     * @param cls 主界面类名；null = 按约定用 [MANAGER_MAIN_ACTIVITY]。
     */
    private data class ManagerApp(val pkg: String, val cls: String?)

    /** 外部注册的管理器应用（见 [addStandaloneShizukuPackage]）。写少读多 → COW 列表，避免遍历时被并发改。 */
    private val registeredManagerApps = CopyOnWriteArrayList<ManagerApp>()

    /** 跳转候选 = 外部注册的 + 官方/常见包（后者按约定类名试）。 */
    private val managerCandidates: List<ManagerApp>
        get() = registeredManagerApps + KNOWN_SHIZUKU_PACKAGES.map { ManagerApp(it, null) }

    fun isInstalled(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 请求 Shizuku 权限（Shizuku 自己弹出授权界面）。宿主未引入任何 Shizuku API 时回调 false，不抛异常。 */
    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        if (isPermissionGranted()) {
            onResult(true)
            return
        }
        runCatching {
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(code: Int, result: Int) {
                    if (code == PERMISSION_CODE) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        onResult(isPermissionGranted())
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_CODE)
        }.onFailure {
            // 宿主排除了 Shizuku 依赖（无 rikka.shizuku.Shizuku 类）：这里必然 NoClassDefFoundError
            onResult(false)
        }
    }

    @Volatile
    private var boundArgs: Shizuku.UserServiceArgs? = null

    @Volatile
    private var boundConn: ServiceConnection? = null

    @Volatile
    private var userService: IAutomationUserService? = null

    /** 是否已有可复用的 UserService 实例（纯查询，不触发绑定、不阻塞）。 */
    fun isUserServiceBound(): Boolean = userService != null

    /**
     * 绑定 shell UserService（默认最长等待 10s）。已绑定则直接返回。宿主未引入任何 Shizuku API 时返回 null，不抛异常。
     *
     * ⚠ Shizuku 的 ServiceConnection 回调由 `ShizukuServiceConnection` 经 **MAIN_HANDLER.post**
     * 投递到主线程，所以本方法**绝不能在主线程调用**——否则主线程被 `latch.await` 阻塞、回调永远
     * 排不上队，必然一路等到超时。
     */
    fun bind(
        context: Context,
        timeoutMs: Long = 10_000,
        onLog: (String) -> Unit = {}
    ): IAutomationUserService? {
        if (userService != null) {
            onLog("UserService 已绑定（复用）")
            return userService
        }
        return try {
            onLog("bindUserService 调用中（最长 ${timeoutMs}ms）...")
            val latch = CountDownLatch(1)
            val ref = AtomicReference<IAutomationUserService?>(null)
            // UserService 进程「还没握手就退出」标记（onServiceDisconnected 先于 onServiceConnected）
            val diedBeforeConnected = AtomicBoolean(false)
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, AutomationUserService::class.java)
            ).daemon(false).processNameSuffix("service").debuggable(false).version(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    ref.set(if (binder != null) IAutomationUserService.Stub.asInterface(binder) else null)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    userService = null
                    ref.set(null)
                    // 关键：进程死亡同样要立即结束等待。否则「启动即崩溃」要白等满整个超时
                    // （bind 默认 10s / 连接路径 15s）才返回失败。
                    diedBeforeConnected.set(true)
                    latch.countDown()
                }
            }
            boundArgs = args
            boundConn = conn
            Shizuku.bindUserService(args, conn)
            val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val svc = ref.get()
            when {
                !completed ->
                    onLog("⚠ bindUserService 超时 ${timeoutMs}ms（Shizuku 未运行 / UserService 进程未启动）")
                svc == null && diedBeforeConnected.get() ->
                    onLog("⚠ UserService 进程启动后立即退出（onServiceDisconnected）——宿主 Application 在 app_process 环境初始化失败，加固/加壳宿主常见；shell 改走 newProcess 直连")
            }
            userService = svc
            svc
        } catch (t: Throwable) {
            // 宿主排除了 Shizuku 依赖（无 rikka.shizuku.Shizuku 类）：这里必然 NoClassDefFoundError
            onLog("✗ Shizuku API 不可用（${t.javaClass.simpleName}: ${t.message}）")
            null
        }
    }

    fun unbind() {
        try {
            boundArgs?.let { a -> boundConn?.let { c -> Shizuku.unbindUserService(a, c, true) } }
        } catch (_: Throwable) {
        }
        boundArgs = null
        boundConn = null
        userService = null
    }

    /**
     * 打开 Shizuku 管理界面，**两种形态都不需要宿主写任何代码、也不用改清单**：
     *
     * 1) manager 已并入**本**宿主 APK（com.github.jeraypop.Shizuku:manager）——
     *    此时官方独立 Shizuku App 可能根本没装，正确跳转目标是【宿主自己】的
     *    moe.shizuku.manager.MainActivity。用 Class.forName 探测：官方 dev.rikka.shizuku:api
     *    只含 rikka.shizuku.* 不含 moe.shizuku.manager.*，所以探测结果只取决于实际打进
     *    APK 的依赖，两种导入方式无需任何配置切换。
     * 2) 管理器在**别的应用**里（独立的 Shizuku App，或另一个把 manager 并入自身的宿主 App）：
     *    2a) [discoverManagerPackage] 用固定 action [MANAGER_PROBE_ACTION] 反查管理器所在包，
     *        再按已知类名显式跳转 `moe.shizuku.manager.MainActivity`。**这是零配置主路径**：
     *        那个主界面 `exported=true` 却没有任何 intent-filter（见 [ManagerApp]），
     *        靠扫组件永远找不到，但「声明了探针 action 的那个包」就是它所在的应用。
     *    2b) 已注册候选（[addStandaloneShizukuPackage]）+ 官方/常见包名（[KNOWN_SHIZUKU_PACKAGES]）；
     *    2c) 兜底扫描：在本应用可见的**带启动页应用**里逐个探测主界面组件是否存在
     *        （覆盖 fork 把探针 action 也改掉、或管理器宿主另有启动页的情形）；
     *    2d) 最后退到候选应用的启动页 —— 主界面跳不过去时至少把那个宿主 App 打开。
     *
     * 包可见性（Android 11+）由**本库自己**的 manifest `<queries>` 提供（探针 action +
     * MAIN/LAUNCHER + 官方包名），宿主不必声明任何东西。
     *
     * @return 成功拉起任一界面返回 true，都失败返回 false。
     */
    @JvmStatic
    @JvmOverloads
    fun openShizuku(context: Context = appContext): Boolean {
        val pm = context.packageManager

        // 1) manager 已并入宿主 APK：打开自己包内的 manager 主界面（同 UID，不受 exported 限制）。
        //    启动失败也不再直接 return —— 别的应用里可能还有一个能用的管理器，继续往下试。
        runCatching { Class.forName(MANAGER_MAIN_ACTIVITY) }.getOrNull()?.let { cls ->
            val intent = Intent(context, cls)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startActivity(context, intent)) return true
        }

        // 2a) 零配置主路径：固定 action 反查管理器所在包 → 按已知类名显式跳它的主界面。
        discoverManagerPackage(pm)?.let { pkg ->
            if (startComponent(context, pkg, MANAGER_MAIN_ACTIVITY)) return true
            // 包里明明有探针组件、却跳不过主界面（类名被 fork 改过等）：清缓存，下次重新探测
            discoveredManagerPackage = null
        }

        // 2b) 候选应用（外部注册 + 官方包名）按组件名直跳管理器主界面。
        //     组件不存在 / 不可见时 startActivity 抛异常，被 startComponent 吞掉后继续往下走。
        for (app in managerCandidates) {
            if (startComponent(context, app.pkg, app.cls ?: MANAGER_MAIN_ACTIVITY)) return true
        }

        // 2c) 兜底扫描：在本应用**可见**的「带启动页应用」里逐个探测管理器主界面组件是否存在。
        //     可见范围来自 manifest 里那条 `<intent> MAIN + LAUNCHER`（Android 11+ 包可见性），
        //     可见性按**包**生效 —— 某包一旦可见，就能读到并拉起它的任意组件。
        //     ⚠ 不要换成 `<queries><provider>`：那个元素按 authority 匹配、android:authorities
        //     是必填属性，写类名既编不过也不起作用。
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPackages = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrNull()
            .orEmpty()
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
        for (pkg in launcherPackages) {
            if (pkg == context.packageName) continue
            if (hasActivity(pm, pkg, MANAGER_MAIN_ACTIVITY) &&
                startComponent(context, pkg, MANAGER_MAIN_ACTIVITY)
            ) return true
        }

        // 2d) 最后退化到候选应用的**启动页**：主界面跳不过去时，至少把那个宿主 App 打开
        //     （用户可在里面自己找到管理器入口），总比什么都不做强。
        for (app in managerCandidates) {
            val intent = pm.getLaunchIntentForPackage(app.pkg) ?: continue
            if (startActivity(context, intent)) return true
        }
        return false
    }

    private fun startActivity(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    /** 按「包名 + 类名」显式跳转；组件不存在、未导出、或包不可见时返回 false（不抛）。 */
    private fun startComponent(context: Context, pkg: String, cls: String): Boolean = try {
        val intent = Intent().apply {
            component = ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * 该包内是否存在名为 [cls] 的活动（要求包对本应用可见）。
     *
     * 跳转前先用它探一下，避免对上百个无关应用逐个 `startActivity` 失败刷日志；
     * 探测同样是一次 binder 调用，成本与直接尝试启动一样。
     */
    private fun hasActivity(pm: PackageManager, pkg: String, cls: String): Boolean =
        runCatching { pm.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess

    /**
     * 零配置探测「Shizuku 管理器所在应用」的包名 —— 用固定 action [MANAGER_PROBE_ACTION] 反查：
     * 谁声明了它，谁就是管理器所在应用（官方 App、改包名的 fork、把 manager 并入自身的宿主，
     * 用的都是同一份 manager 清单，这个 action 恒为字面量、一模一样）。
     *
     * 自己命中自己时跳过：本应用也并入了 manager 的情形，已由 [openShizuku] 步骤 1 直接处理。
     * 结果命中即缓存（管理器不会中途换包名）；探测失败不缓存，下次重新试。
     */
    private fun discoverManagerPackage(pm: PackageManager): String? {
        discoveredManagerPackage?.let { return it }
        val probe = Intent(MANAGER_PROBE_ACTION).addCategory(Intent.CATEGORY_DEFAULT)
        val hit = runCatching { pm.queryIntentActivities(probe, 0) }.getOrNull().orEmpty()
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { it != appContext.packageName }
        if (hit != null) discoveredManagerPackage = hit
        return hit
    }

    /** 供外部查询当前导入形态：true = fork manager 已并入宿主 APK；false = 仅官方 api/provider。 */
    fun isManagerMergedIntoHost(): Boolean = runCatching {
        Class.forName(MANAGER_MAIN_ACTIVITY)
    }.isSuccess

    /**
     * 手工注册「Shizuku 管理器所在应用」—— **通常无需调用**：管理器所在包已由
     * [discoverManagerPackage] 用固定 action 自动反查（官方 App、改包名的 fork、
     * 把 manager 并入自身的宿主都能自动命中，宿主零代码）。只有那个 fork 连
     * 探针 action 都改掉时才需要用它把包名补进来。
     *
     * 注册后 [openShizuku] 会按「包名 + 类名」显式跳转它的管理器主界面。
     * 在 `Application.onCreate` 之类的早期时机调用一次即可（进程内生效，不落盘）。
     *
     * ⚠ Android 11+ 包可见性：注册的包最好也在使用方 manifest 的 `<queries>` 里声明
     *   （`<package android:name="pkg"/>`），否则显式跳转仍会被拦下。
     *
     * @param pkg           管理器所在应用包名。
     * @param activityClass 管理器主界面类名；null（默认）按约定用 `moe.shizuku.manager.MainActivity`。
     *                      只有对方连类名都改了才需要传。
     */
    @JvmStatic
    @JvmOverloads
    fun addStandaloneShizukuPackage(pkg: String, activityClass: String? = null) {
        val name = pkg.trim()
        if (name.isEmpty()) return
        registeredManagerApps.add(ManagerApp(name, activityClass?.trim()?.ifEmpty { null }))
    }
}
