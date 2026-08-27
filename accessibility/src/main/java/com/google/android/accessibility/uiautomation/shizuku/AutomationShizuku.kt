package com.google.android.accessibility.uiautomation.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
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
}
