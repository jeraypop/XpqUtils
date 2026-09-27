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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /** 官方/常见独立 Shizuku App 包名，兜底用。 */
    private val KNOWN_SHIZUKU_PACKAGES = arrayOf("moe.shizuku.privileged.api", "rikka.shizuku")

    /** 外部注册的自定义 fork 包名（见 addStandaloneShizukuPackage）。 */
    private val standaloneExtraPackages = mutableListOf<String>()

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

    /** 请求 Shizuku 权限（Shizuku 自己弹出授权界面）。 */
    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        if (isPermissionGranted()) {
            onResult(true)
            return
        }
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
    }

    @Volatile
    private var boundArgs: Shizuku.UserServiceArgs? = null

    @Volatile
    private var boundConn: ServiceConnection? = null

    @Volatile
    private var userService: IAutomationUserService? = null

    /** 绑定 shell UserService（默认最长等待 10s）。已绑定则直接返回。 */
    fun bind(
        context: Context,
        timeoutMs: Long = 10_000,
        onLog: (String) -> Unit = {}
    ): IAutomationUserService? {
        if (userService != null) {
            onLog("UserService 已绑定（复用）")
            return userService
        }
        onLog("bindUserService 调用中（最长 ${timeoutMs}ms）...")
        val latch = CountDownLatch(1)
        val ref = AtomicReference<IAutomationUserService?>(null)
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
            }
        }
        boundArgs = args
        boundConn = conn
        Shizuku.bindUserService(args, conn)
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) onLog("⚠ bindUserService 超时（Shizuku 未运行 / 进程未启动）")
        userService = ref.get()
        return userService
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
     * 打开 Shizuku 管理界面，按导入方式自动分流：
     *
     * 1) fork 的 manager 以库形式并入宿主 APK（com.github.jeraypop.Shizuku:manager）——
     *    此时官方独立 Shizuku App 可能根本没装，正确跳转目标是【宿主自己】的
     *    moe.shizuku.manager.MainActivity。用 Class.forName 探测：官方 dev.rikka.shizuku:api
     *    只含 rikka.shizuku.* 不含 moe.shizuku.manager.*，所以探测结果只取决于实际打进
     *    APK 的依赖，两种导入方式无需任何配置切换。
     * 2) 独立 Shizuku App——不依赖固定包名：
     *    2a) 按主界面类名 moe.shizuku.manager.MainActivity 扫描已安装应用（fork 改了
     *        applicationId 也能命中，fork 一般只改包名不改类名）；
     *    2b) 已知包名兜底（类名也被改掉但包名仍是官方/常见值的情形）；
     *    2c) 都没有时可用 addStandaloneShizukuPackage() 注册自定义 fork 包名。
     *
     * @return 成功拉起任一界面返回 true，都失败返回 false。
     */
    @JvmStatic
    @JvmOverloads
    fun openShizuku(context: Context = appContext): Boolean {
        // 1) manager 已并入宿主 APK：打开自己包内的 manager 主界面（同 UID，不受 exported 限制）
        val managerActivity = runCatching {
            Class.forName(MANAGER_MAIN_ACTIVITY)
        }.getOrNull()
        if (managerActivity != null) {
            return try {
                val intent = Intent(context, managerActivity)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        val pm = context.packageManager

        // 2a) 按"主 Activity 类名"扫描：不依赖包名，applicationId 被改过的 fork 也能找到。
        //     注意 Android 11+ 包可见性：目标是改名 fork 时需在 manifest <queries> 里
        //     声明其包名或 <provider android:name="rikka.shizuku.ShizukuProvider"/>。
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrNull().orEmpty()
        for (info in launchers) {
            if (info.activityInfo?.name == MANAGER_MAIN_ACTIVITY) {
                val intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: continue
                if (startActivity(context, intent)) return true
            }
        }

        // 2b) 已知包名兜底 + 2c) 外部注册的自定义 fork 包名（类名也被改掉的情形）
        for (pkg in (standaloneExtraPackages + KNOWN_SHIZUKU_PACKAGES)) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
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

    /** 供外部查询当前导入形态：true = fork manager 已并入宿主 APK；false = 仅官方 api/provider。 */
    fun isManagerMergedIntoHost(): Boolean = runCatching {
        Class.forName(MANAGER_MAIN_ACTIVITY)
    }.isSuccess

    /**
     * 注册自定义 fork 的独立 Shizuku App 包名（当 fork 同时改掉包名和类名时，
     * 扫描已无法识别，用这个方法把它的包名加进跳转候选）。
     */
    @JvmStatic
    fun addStandaloneShizukuPackage(pkg: String) {
        if (pkg.isNotBlank()) standaloneExtraPackages.add(pkg)
    }
}
