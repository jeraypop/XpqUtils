package com.google.android.accessibility.ext.shell

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.android.accessibility.sidecar.SidecarMain
import com.google.android.accessibility.sidecar.SidecarProtocol
import com.google.android.accessibility.sidecar.XpqSidecarProvider

/**
 * 内置 ADB 模式下的 shell 侧常驻进程（Sidecar）客户端。
 *
 * Sidecar 以 shell（uid 2000）身份运行，能力等价 Shizuku：
 *  - [wrap]：把系统服务 binder 包成「以 shell 身份 transact」的包装器（替代 ShizukuBinderWrapper）；
 *  - [exec]：以 shell 身份执行命令（替代 Shizuku.newProcess）。
 *
 * 生命周期：配对/连接好内置 ADB 后，[ensureStarted] 用
 * `CLASSPATH=<apk> app_process ... SidecarMain ...` 拉起；进程通过宿主 ContentProvider
 * 把 binder 交回（见 [XpqSidecarProvider]）。App 进程死亡时 provider binder 随之失效，Sidecar 自退出。
 */
object XpqSidecar {
    private const val TAG = "XpqAdbShell"

    @Volatile
    private var binder: IBinder? = null

    /** Sidecar 进程 uid，正常应为 2000。 */
    @Volatile
    var sidecarUid: Int = -1
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 由 [XpqSidecarProvider] 回调。 */
    internal fun onAttached(binder: IBinder, uid: Int) {
        this.binder = binder
        sidecarUid = uid
        lastError = null
        Log.i(TAG, "sidecar attached uid=$uid pid=${runCatching { binder.pingBinder() }.getOrDefault(false)}")
    }

    @JvmStatic
    fun isReady(): Boolean = binder?.let { runCatching { it.pingBinder() }.getOrDefault(false) } == true

    /** 当前 Sidecar binder；未连接返回 null。 */
    @JvmStatic
    fun binder(): IBinder? = binder?.takeIf { runCatching { it.pingBinder() }.getOrDefault(false) }

    /** 把系统服务 binder 包成 shell 身份（等价 ShizukuBinderWrapper）。 */
    @JvmStatic
    fun wrap(target: IBinder): IBinder = SidecarBinderWrapper(target)

    /**
     * 按当前选择的通道返回包装器：
     *  - 选了内置 ADB → [SidecarBinderWrapper]（shell 来自 Sidecar 进程）
     *  - 否则 → Shizuku 的 BinderWrapper（保持原有行为）
     */
    @JvmStatic
    fun wrapShellBinder(target: IBinder): IBinder =
        if (XpqAdbShell.isPreferred()) SidecarBinderWrapper(target)
        else rikka.shizuku.ShizukuBinderWrapper(target)

    /**
     * 确保 Sidecar 已启动并交回 binder。阻塞，需在子线程调用。
     *
     * @return true 表示已就绪；false 时 [lastError] 有原因。
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun ensureStarted(context: Context, timeoutMs: Long = 8_000L): Boolean {
        if (isReady()) return true
        if (!XpqAdbShell.ensureConnected(context)) {
            lastError = "内置 ADB 未连接：${XpqAdbShell.lastError ?: "请先配对/连接"}"
            return false
        }
        // 旧进程可能还活着（App 刚重启，provider 还没被重新 attach），先等它一下
        if (awaitReady(800L)) return true
        if (!start(context)) return false
        val ok = awaitReady(timeoutMs)
        if (!ok) lastError = "Sidecar 启动超时（$timeoutMs ms）"
        return ok
    }

    /** 以 shell 身份执行命令；未就绪返回 null。阻塞，需在子线程调用。 */
    @JvmStatic
    fun exec(command: String): ShellResult? {
        val b = binder() ?: run {
            lastError = "Sidecar 未就绪"
            return null
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SidecarProtocol.DESCRIPTOR)
            data.writeString(command)
            if (!b.transact(SidecarProtocol.TX_EXEC, data, reply, 0)) {
                lastError = "Sidecar exec 事务被拒绝"
                return null
            }
            reply.setDataPosition(0)
            ShellResult(reply.readInt(), reply.readString() ?: "")
        } catch (t: Throwable) {
            lastError = "Sidecar exec 异常：${t.javaClass.simpleName}: ${t.message}"
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** 状态摘要，供 UI/日志展示。 */
    @JvmStatic
    fun status(): String {
        val b = binder
        return "sidecar=" + (if (b == null) "未建立" else "uid=$sidecarUid, alive=${runCatching { b.pingBinder() }.getOrDefault(false)}") +
                (lastError?.let { "，最后错误=$it" } ?: "")
    }

    /** 主动停掉 Sidecar 进程（下一轮 [ensureStarted] 会重新拉起）。 */
    @JvmStatic
    fun stop(context: Context) {
        binder = null
        sidecarUid = -1
        runCatching { XpqAdbShell.exec("pkill -f ${SidecarProtocol.PROCESS_NAME}") }
    }

    private fun start(context: Context): Boolean {
        val authority = findAuthority(context)
        if (authority.isNullOrBlank()) {
            lastError = "找不到 Sidecar provider，检查清单是否合并成功"
            return false
        }
        val apk = context.packageCodePath
        if (apk.isNullOrBlank()) {
            lastError = "取不到 APK 路径"
            return false
        }
        val mainClass = SidecarMain::class.java.name
        // 先按进程名精确清掉旧进程（不能用 pkill -f，会匹配到本条命令自身）；
        // < /dev/null + 重定向保证 shell 命令立即返回、不占用 ADB 通道；
        // 末尾 echo 用输出确认命令确实被执行（不依赖后台进程的退出码）
        val killOld = "for p in \$(ps -A -o PID,NAME | awk '\$2==\"${SidecarProtocol.PROCESS_NAME}\"{print \$1}'); " +
                "do kill -9 \$p 2>/dev/null; done; "
        val cmd = killOld +
                "CLASSPATH='$apk' app_process /system/bin " +
                "--nice-name=${SidecarProtocol.PROCESS_NAME} $mainClass " +
                "'$authority' ${Process.myUid() / 100000} '${context.packageName}' " +
                "< /dev/null > /dev/null 2>&1 & echo launched"
        val r = XpqAdbShell.exec(cmd)
        if (r == null) {
            lastError = "拉起 Sidecar 失败：${XpqAdbShell.lastError ?: "ADB 通道不可用"}"
            return false
        }
        Log.i(TAG, "sidecar 启动命令 exit=${r.exitCode} out=${r.output.trim().take(120)}")
        return true
    }

    private fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isReady()) return true
            SystemClock.sleep(80L)
        }
        return isReady()
    }

    /** 从清单里查 Sidecar provider 的 authority（兼容 applicationIdSuffix）。 */
    private fun findAuthority(context: Context): String? {
        val byClass = runCatching {
            context.packageManager.queryContentProviders(context.packageName, Process.myUid(), 0)
                ?.firstOrNull { it.name == XpqSidecarProvider::class.java.name }
                ?.authority
        }.getOrNull()
        return byClass ?: "${context.packageName}.xpq.sidecar"
    }
}
