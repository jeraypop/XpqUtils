package com.google.android.accessibility.ext.shell

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 内置 ADB（不依赖 Shizuku）的对外入口。
 *
 * 当前阶段用于验证：配对 → 连接 → 执行命令，确认能拿到 shell 身份。
 * 验证通过后再把它接到虚拟屏的后端选择（`XpqVDisplay`）与模式对话框。
 *
 * 用法：
 * ```
 * XpqAdbShell.pair(context, "127.0.0.1", 37999, "123456")   // 子线程
 * XpqAdbShell.connect(context)                              // 子线程
 * XpqAdbShell.exec("id")                                     // 子线程
 * ```
 */
object XpqAdbShell {
    private const val TAG = "XpqAdbShell"
    private const val PREF = "xpq_adb_shell"
    private const val KEY_HOST = "host"
    /** 配对端口：只在「使用配对码配对设备」弹窗里出现，每次配对都不同。 */
    private const val KEY_PAIR_PORT = "pair_port"
    /** 连接端口：无线调试主页显示的「IP 地址和端口」，与配对端口不同。 */
    private const val KEY_CONNECT_PORT = "connect_port"
    /** TCP 模式开关：adbd 固定监听 [DEFAULT_TCP_PORT]，之后重连不需要 WiFi。 */
    private const val KEY_TCP_MODE = "tcp_mode"
    /** 自定义 TCP 端口（留空 = 默认 5555）。 */
    private const val KEY_TCP_PORT = "tcp_port"
    /** 开机自启动：BOOT_COMPLETED 是否自动恢复内置 ADB 通道。 */
    private const val KEY_AUTO_START = "auto_start"
    /** 看门狗：通道意外断开时自动重启。 */
    private const val KEY_WATCHDOG = "watchdog"

    /** TCP 模式默认端口（与 Shizuku 一致）。 */
    private const val DEFAULT_TCP_PORT = 5555

    /** 本机 adbd 通过 127.0.0.1 访问，不需要局域网 IP。 */
    private const val DEFAULT_HOST = "127.0.0.1"

    @Volatile
    private var bridge: ShellBridge? = null

    /** 最近一次失败原因，供 UI/日志展示。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 配对失败的类别：决定浮窗把提示落在哪个字段上（对应 Shizuku 的分类错误）。 */
    enum class PairError { PORT, CODE, KEY, OTHER }

    /** 最近一次配对失败的类别。 */
    @Volatile
    var lastPairErrorKind: PairError? = null
        private set

    /**
     * 配对；[host] 一般填 127.0.0.1。
     * [pairPort] 传 0 时用 mDNS 自动找配对端口；[code] 是「使用配对码配对设备」弹窗里的 6 位码。
     */
    @JvmStatic
    @JvmOverloads
    fun pair(context: Context, host: String, pairPort: Int, code: String): Boolean {
        if (!ensureInternetOrReport(context)) return false
        val port = if (pairPort > 0) pairPort else {
            AdbMdns.discover(context, AdbMdns.PAIRING)?.second ?: run {
                lastPairErrorKind = PairError.PORT
                lastError = "没找到配对端口：本机 mDNS 拿不到端口，" +
                        "请把系统配对弹窗里「IP 地址和端口」冒号后面的端口填进来再配对"
                return false
            }
        }
        rememberHost(context, host, port)
        val b = KadbShellBridge(context.applicationContext, host, port)
        val ok = b.pair(code)
        if (ok) {
            lastPairErrorKind = null
            lastError = null
        } else {
            val kind = classifyPairError(b.lastPairThrowable)
            lastPairErrorKind = kind
            lastError = describePairError(context, kind, b.lastPairThrowable, port)
        }
        Log.i(TAG, "pair $host:$port -> $ok")
        return ok
    }

    /** 把底层异常翻译成三类（端口 / 配对码 / 密钥），提示才能指到正确的下一步。 */
    private fun classifyPairError(t: Throwable?): PairError = when {
        t == null -> PairError.OTHER
        t is java.net.ConnectException || t is java.net.SocketTimeoutException -> PairError.PORT
        t is com.flyfishxu.kadb.cert.KadbCertException -> PairError.KEY
        t is javax.net.ssl.SSLException -> PairError.CODE
        else -> when {
            // SPAKE2 交换失败 / peer info 解不开 = 配对码不对（或已过期）
            t.message?.contains("Exchanging message") == true -> PairError.CODE
            t.message?.contains("exchange peer info") == true -> PairError.CODE
            t.message?.contains("trust missing") == true -> PairError.CODE
            t.message?.contains("refused", true) == true -> PairError.PORT
            else -> PairError.OTHER
        }
    }

    private fun describePairError(context: Context, kind: PairError, t: Throwable?, port: Int): String {
        val detail = t?.let { "${it.javaClass.simpleName}: ${it.message}" }
        return when (kind) {
            PairError.PORT -> {
                // 常见误填：把「无线调试页」的连接端口当成配对端口
                val hint = if (port == savedConnectPort(context))
                    "；这个端口像是无线调试页的连接端口，配对端口要用「使用配对码配对设备」弹窗里的那个" else ""
                "配对端口 $port 连不上：端口不对，或系统「使用配对码配对设备」弹窗已关闭" +
                        "（配对服务只在弹窗打开时存在）$hint"
            }
            PairError.CODE -> "配对码不对（或已过期），请重新打开系统配对弹窗取 6 位码"
            PairError.KEY -> "本机 ADB 密钥不可用：${detail ?: "读写失败"}"
            PairError.OTHER -> "配对失败：${detail ?: "端口或配对码不对，确认系统配对弹窗仍开着"}"
        }
    }

    /**
     * 连接；[port] 是无线调试主页显示的连接端口（不是配对端口）。
     *
     * 顺序：用户本次填的端口 → 上次成功连接记住的端口 → mDNS 自动发现。
     * 记住的端口优先是为了「只填一次」：正常启动直接秒连，不用每次等 mDNS。
     */
    @JvmStatic
    @JvmOverloads
    fun connect(context: Context, host: String? = null, port: Int = 0): Boolean =
        connectInternal(context, host, port, attempts = 1)

    /**
     * 配对成功后的连接：adbd 对刚写入的密钥可能有一两秒延迟，
     * 照 Shizuku 的 connectWithRetry 短暂重试，避免「配对成功但连接失败」。
     */
    @JvmStatic
    fun connectAfterPair(context: Context): Boolean =
        connectInternal(context, null, 0, attempts = 5)

    /**
     * 内置 ADB 要自己建 TCP socket（配对 + 连接都依赖），宿主把 INTERNET 权限移除后
     * 只会拿到 `SocketException: EPERM (Operation not permitted)`，这里提前给出可操作的提示。
     */
    private fun hasInternetPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.INTERNET) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun ensureInternetOrReport(context: Context): Boolean {
        if (hasInternetPermission(context)) return true
        lastError = "应用没有 INTERNET 权限：内置 ADB 需要建立本地 TCP 连接，" +
                "请在宿主 AndroidManifest 里声明 android.permission.INTERNET" +
                "（若用了 tools:node=\"remove\" 请去掉）"
        return false
    }

    private fun connectInternal(
        context: Context,
        host: String?,
        port: Int,
        attempts: Int,
    ): Boolean {
        if (!ensureInternetOrReport(context)) return false
        val h = host?.takeIf { it.isNotBlank() } ?: savedHost(context) ?: DEFAULT_HOST
        val candidates = LinkedHashSet<Int>()
        if (port > 0) candidates += port
        // TCP 模式优先：adbd 已固定在 5555 时不需要 WiFi / mDNS，直接连（照 Shizuku 的做法）
        if (isTcpMode(context)) candidates += tcpPort(context)
        savedConnectPort(context).takeIf { it > 0 }?.let { candidates += it }

        // 没有候选端口时才先做一次 mDNS（有候选就先省掉这段等待）
        var discoveryDone = false
        if (candidates.isEmpty()) {
            discoveryDone = true
            AdbMdns.discover(context, AdbMdns.CONNECT)?.second?.takeIf { it > 0 }?.let { candidates += it }
        }
        if (candidates.isEmpty()) {
            lastError = "没找到无线调试端口，请确认已打开无线调试，或手动填写端口"
            return false
        }

        var round = 0
        var tcpSwitchTried = false
        while (true) {
            for (p in candidates.toList()) {
                if (tryConnect(context, h, p)) {
                    // 连上的是无线调试端口，而用户开了 TCP 模式：把 adbd 切到固定端口，
                    // 这样之后没有 WiFi / 无线调试关闭也能重连（只尝试一次，失败就保留原连接）
                    if (isTcpMode(context) && p != tcpPort(context) && !tcpSwitchTried) {
                        tcpSwitchTried = true
                        if (switchToTcp(context)) return true
                        // 切换失败：尽量把刚才那条连接恢复回来
                        return tryConnect(context, h, p)
                    }
                    return true
                }
            }
            // 已有候选端口全失败，再补一次自动发现（端口可能重开无线调试后变了）
            if (!discoveryDone) {
                discoveryDone = true
                AdbMdns.discover(context, AdbMdns.CONNECT)?.second
                    ?.takeIf { it > 0 && it !in candidates }
                    ?.let { discovered ->
                        candidates += discovered
                        if (tryConnect(context, h, discovered)) {
                            // 自动发现这条路径同样要切 TCP 模式，否则重连会一直依赖无线调试
                            if (isTcpMode(context) && discovered != tcpPort(context) && !tcpSwitchTried) {
                                tcpSwitchTried = true
                                if (switchToTcp(context)) return true
                                return tryConnect(context, h, discovered)
                            }
                            return true
                        }
                    }
            }
            round++
            if (round >= attempts) break
            SystemClock.sleep(500L * round) // 递增退避：0.5s、1s、1.5s、2s
        }
        // 失败收尾：TCP 模式下补一句可操作的原因（设备重启后 5555 会失效）
        if (isTcpMode(context)) {
            lastError = (lastError ?: "连接失败") +
                    "。TCP 模式下设备重启或关闭无线调试后，5555 端口会失效，" +
                    "需要先打开无线调试并连一次，才能重新固定到 5555"
        }
        return false
    }

    /**
     * 让 adbd 切到固定 TCP 端口（等价 adb host 的 `tcpip:<port>`）。
     *
     * 切换会让 adbd 重启，当前连接随之失效，所以这里失败也会断开旧客户端；
     * 成功则等新端口起来后重连并记住它，此后的重连不再依赖 WiFi / 无线调试。
     */
    private fun switchToTcp(context: Context): Boolean {
        val b = bridge as? KadbShellBridge ?: return false
        val target = tcpPort(context)
        Log.i(TAG, "adbd -> tcpip:$target")
        val ok = b.switchToTcpPort(target)
        // adbd 无论切换是否成功都会重启，旧连接已经不能用了
        bridge = null
        b.disconnect()
        if (!ok) {
            lastError = "切换 TCP 模式失败"
            return false
        }
        repeat(12) {
            SystemClock.sleep(350)
            if (tryConnect(context, DEFAULT_HOST, target)) {
                Log.i(TAG, "tcp mode ready on $target")
                return true
            }
        }
        lastError = "TCP 模式端口 $target 连接失败"
        return false
    }

    /** 真正建立一条连接；成功才记住端口，避免把错误端口覆盖到记忆里。 */
    private fun tryConnect(context: Context, host: String, port: Int): Boolean {
        bridge?.disconnect()
        val b = KadbShellBridge(context.applicationContext, host, port)
        val ok = b.connect()
        bridge = if (ok) b else null
        lastError = if (ok) null else {
            "连接失败：${b.lastConnectError ?: "未配对、配对失效或端口已变"}"
        }
        if (ok) {
            rememberHost(context, host, savedPairPort(context))
            rememberConnectPort(context, port)
        }
        Log.i(TAG, "connect $host:$port -> $ok")
        return ok
    }

    /** 自动发现连接端口（无线调试主页那个）；找不到返回 0。阻塞，需在子线程调用。 */
    @JvmStatic
    fun discoverConnectPort(context: Context): Int =
        AdbMdns.discover(context, AdbMdns.CONNECT)?.second ?: 0

    /** 自动发现配对端口（配对弹窗里的那个）；找不到返回 0。阻塞，需在子线程调用。 */
    @JvmStatic
    fun discoverPairPort(context: Context): Int =
        AdbMdns.discover(context, AdbMdns.PAIRING)?.second ?: 0

    @JvmStatic
    fun isConnected(): Boolean = bridge?.isConnected() == true

    /**
     * 供库内通道调用：已连接直接返回 true；否则在节流窗口外尝试一次自动重连
     * （进程重启后 [bridge] 会丢，但配对/端口已持久化，可静默重连）。
     *
     * 节流是必须的：无线调试没开时 [connect] 会走 mDNS（最长 5s）再失败，
     * 否则每条命令都要付这份代价。阻塞，需在子线程调用。
     */
    @JvmStatic
    fun ensureConnected(context: Context): Boolean {
        if (isConnected()) return true
        val now = SystemClock.elapsedRealtime()
        val prev = autoConnectAt.get()
        if (now - prev < AUTO_CONNECT_INTERVAL_MS) return false
        // CAS 失败说明别的线程正在重连，本次直接当不可用，避免并发建连
        if (!autoConnectAt.compareAndSet(prev, now)) return false
        return connect(context)
    }

    /** 自动重连最小间隔。 */
    private const val AUTO_CONNECT_INTERVAL_MS = 15_000L
    private val autoConnectAt = AtomicLong(0L)

    /** 已连接的内置 ADB 通道；没连上返回 null。供库内其它通道（如 shell 命令）复用。 */
    internal fun connectedBridge(): ShellBridge? = bridge?.takeIf { it.isConnected() }

    /**
     * 是否优先使用内置 ADB 作为 shell 通道（模式对话框「内置 ADB 模式」的持久化开关）。
     * 开启后即使 Shizuku 在运行，[com.google.android.accessibility.uiautomation.engine.InvisibleAutomation.exec]
     * 也会先走内置 ADB。
     */
    @JvmStatic
    fun setPreferred(context: Context, preferred: Boolean) {
        com.google.android.accessibility.ext.utils.MMKVUtil.put(
            com.google.android.accessibility.ext.utils.MMKVConst.KEY_SHELL_PREFER_BUILTIN,
            preferred
        )
        // 切到内置 ADB 时，若用户开过看门狗，这里把它带起来；退出时做收尾回收
        if (preferred) startWatchdog(context) else releaseForModeSwitch(context)
    }

    /**
     * 离开内置 ADB 模式（切到无障碍 / Shizuku 模式）时的收尾：
     * 停看门狗、停 Sidecar、断开 Kadb。
     *
     * 故意**不**发送 `usb:`：那会让 adbd 重启并关掉 5555，用户的无线调试
     * （以及通过 WiFi 连着的电脑 adb）会被一起打断，代价太大。
     * 保活前台服务 [AliveFGService] 也不停——它可能还被宿主用于其它保活用途。
     * 开关偏好保留，切回内置模式时继续沿用。
     */
    @JvmStatic
    fun releaseForModeSwitch(context: Context) {
        val app = context.applicationContext
        watchdogRunning.set(false)
        Thread {
            runCatching {
                // 借当前 ADB 通道杀掉 Sidecar（断开后就杀不掉了）
                if (isConnected()) XpqSidecar.stop(app)
            }.onFailure { Log.w(TAG, "release sidecar failed", it) }
            runCatching { disconnect() }
                .onFailure { Log.w(TAG, "release disconnect failed", it) }
            Log.i(TAG, "release for mode switch -> ${status()}")
        }.apply {
            isDaemon = true
            name = "xpq-adb-release"
        }.start()
    }

    @JvmStatic
    fun isPreferred(context: Context): Boolean =
        com.google.android.accessibility.ext.utils.MMKVUtil.get(
            com.google.android.accessibility.ext.utils.MMKVConst.KEY_SHELL_PREFER_BUILTIN,
            false
        )

    /**
     * 无参版本：从全局 Context 读偏好，供没有 context 参数的底层通道使用。
     * Context 还没初始化过时按「未选择内置」处理。
     */
    @JvmStatic
    fun isPreferred(): Boolean = runCatching {
        com.google.android.accessibility.ext.utils.LibCtxProvider.appContext
    }.getOrNull()?.let { isPreferred(it) } ?: false

    /**
     * TCP 模式开关（移植 Shizuku 的「TCP mode」）。
     *
     * 开启后会把 adbd 切到固定端口 [DEFAULT_TCP_PORT]（`tcpip:5555`），
     * 之后重连直接走 `127.0.0.1:5555`，断 WiFi / 关掉无线调试也能连上。
     * 默认开启（与 Shizuku 一致）。
     */
    @JvmStatic
    fun isTcpMode(context: Context): Boolean = prefs(context).getBoolean(KEY_TCP_MODE, true)

    @JvmStatic
    fun setTcpMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TCP_MODE, enabled).apply()
        // 关闭后不再优先 5555：把记住的 TCP 端口清掉，重连回到无线调试端口
        if (!enabled && savedConnectPort(context) == tcpPort(context)) {
            prefs(context).edit().remove(KEY_CONNECT_PORT).apply()
        }
    }

    /** TCP 模式固定端口（默认 5555，可在高级设置里改）。 */
    @JvmStatic
    fun tcpPort(context: Context): Int =
        prefs(context).getInt(KEY_TCP_PORT, DEFAULT_TCP_PORT).takeIf { it in 1..65535 } ?: DEFAULT_TCP_PORT

    /** 修改 TCP 端口；下次连接 / 切换 TCP 模式时生效。 */
    @JvmStatic
    fun setTcpPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_TCP_PORT, port.coerceIn(1, 65535)).apply()
    }

    /** TCP 模式默认端口，供 UI 展示「默认值（5555）」。 */
    @JvmStatic
    fun defaultTcpPort(): Int = DEFAULT_TCP_PORT

    /** 开机自启动（默认开启）：BOOT_COMPLETED 后是否自动恢复内置 ADB 通道。 */
    @JvmStatic
    fun isAutoStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, true)

    @JvmStatic
    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    /** 看门狗开关（默认开启）。 */
    @JvmStatic
    fun isWatchdogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WATCHDOG, true)

    @JvmStatic
    fun setWatchdogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCHDOG, enabled).apply()
        // 关闭时只停循环，不停保活前台服务（它可能还被宿主用于其它用途）
        if (enabled) startWatchdog(context) else watchdogRunning.set(false)
    }

    /** 看门狗是否在运行（循环活着，且保活前台服务在）。 */
    @JvmStatic
    fun isWatchdogRunning(): Boolean =
        watchdogRunning.get() && com.google.android.accessibility.ext.activity.AliveFGService.fgs_ison

    /**
     * 启动看门狗（对应 Shizuku 的 WatchdogService）。
     *
     * 复用库里已有的保活前台服务 [AliveFGService]，不再单独占一个前台服务；
     * 循环随服务生命周期走（服务被系统重建时由 [onForegroundServiceStarted] 恢复）。
     * 只在「内置 ADB 优先 + 开关打开」时生效。
     */
    @JvmStatic
    fun startWatchdog(context: Context) {
        val app = context.applicationContext
        if (!isPreferred(app) || !isWatchdogEnabled(app)) return
        // 前台服务已在跑时不要再调 startFGAlive：它内部会 toast「前台服务已开启」，
        // 而本方法在每次点「确定」切模式（setPreferred）时都会走到，导致重复弹提示。
        if (!com.google.android.accessibility.ext.activity.AliveFGService.fgs_ison) {
            com.google.android.accessibility.ext.utils.AliveUtils.startFGAlive(app, true)
        }
        ensureWatchdogLoop(app)
    }

    /** [AliveFGService] 启动时回调：若看门狗开着就把循环接回来。 */
    @JvmStatic
    fun onForegroundServiceStarted(context: Context) {
        if (isPreferred(context) && isWatchdogEnabled(context)) ensureWatchdogLoop(context)
    }

    /** [AliveFGService] 销毁时回调：循环随服务一起退出。 */
    @JvmStatic
    fun onForegroundServiceStopped() {
        watchdogRunning.set(false)
    }

    /** 看门狗检查间隔。 */
    private const val WATCHDOG_INTERVAL_MS = 30_000L

    /** 循环是否在跑（CAS 防止重复起线程）。 */
    private val watchdogRunning = AtomicBoolean(false)

    private fun ensureWatchdogLoop(context: Context) {
        val app = context.applicationContext
        if (!watchdogRunning.compareAndSet(false, true)) return
        Thread {
            Log.i(TAG, "watchdog loop started")
            while (watchdogRunning.get()) {
                SystemClock.sleep(WATCHDOG_INTERVAL_MS)
                if (!watchdogRunning.get()) break
                if (!isPreferred(app) || !isWatchdogEnabled(app)) {
                    watchdogRunning.set(false)
                    break
                }
                runCatching { watchdogCheck(app) }
                    .onFailure { Log.w(TAG, "watchdog check failed", it) }
            }
            Log.i(TAG, "watchdog loop stopped")
        }.apply {
            isDaemon = true
            name = "xpq-adb-watchdog"
        }.start()
    }

    /**
     * 看门狗单次检查：通道断了就重连并重启 Sidecar。
     */
    @JvmStatic
    internal fun watchdogCheck(context: Context) {
        if (!isPreferred(context) || !isWatchdogEnabled(context)) return
        if (!isConnected()) {
            Log.w(TAG, "watchdog: 通道已断开，自动重连")
            if (connectAfterPair(context)) XpqSidecar.ensureStarted(context)
        } else if (!XpqSidecar.isReady()) {
            Log.w(TAG, "watchdog: Sidecar 不在，重新拉起")
            XpqSidecar.ensureStarted(context)
        }
    }

    /**
     * 立即应用 TCP 模式：已连接就切到固定端口并重连。
     * 阻塞，需在子线程调用；未连接时返回 false，等下次 connect / 自动重连再切。
     */
    @JvmStatic
    fun applyTcpMode(context: Context): Boolean {
        if (!isTcpMode(context)) return false
        if (!isConnected() && !connect(context)) return false
        if (savedConnectPort(context) == tcpPort(context)) return true
        return switchToTcp(context)
    }

    /**
     * 后台自动恢复通道（不阻塞调用方）。
     * 内置 ADB 模式：用持久化的配对密钥 + 记住的端口 / mDNS 自动端口重连，然后拉起 Sidecar。
     * 供开机广播、宿主启动时调用，效果等价 Shizuku「重启后自动重连」。
     */
    @JvmStatic
    fun autoConnect(context: Context) {
        val app = context.applicationContext
        if (!isPreferred(app)) return
        Thread {
            runCatching {
                if (ensureConnected(app)) {
                    XpqSidecar.ensureStarted(app)
                }
            }.onFailure { Log.w(TAG, "autoConnect failed", it) }
            Log.i(TAG, "autoConnect -> ${status()}")
            // 一次性把看门狗带起来（开过才生效）
            runCatching { startWatchdog(app) }
        }.apply {
            isDaemon = true
            name = "xpq-adb-autoconnect"
        }.start()
    }

    /** 记住的主机（默认 127.0.0.1），用于模式对话框回填。 */
    @JvmStatic
    fun rememberedHost(context: Context): String = savedHost(context) ?: DEFAULT_HOST

    /** 记住的配对端口；没配对过返回 0。 */
    @JvmStatic
    fun rememberedPairPort(context: Context): Int = savedPairPort(context)

    /** 记住的连接端口；没连接过返回 0。 */
    @JvmStatic
    fun rememberedConnectPort(context: Context): Int = savedConnectPort(context)

    /** 执行命令；未连接返回 null。 */
    @JvmStatic
    fun exec(command: String): ShellResult? = bridge?.exec(command)
        ?: run {
            lastError = "尚未连接"
            null
        }

    /** 通道状态摘要，便于在 UI/日志里确认走的是哪条通道。 */
    @JvmStatic
    fun status(): String {
        val b = bridge
        return buildString {
            append("通道=").append(b?.name ?: "未建立")
            append("，已连接=").append(b?.isConnected() == true)
            lastError?.let { append("，最后错误=").append(it) }
        }
    }

    @JvmStatic
    fun disconnect() {
        bridge?.disconnect()
        bridge = null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun rememberHost(context: Context, host: String, pairPort: Int) {
        prefs(context).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PAIR_PORT, pairPort)
            .apply()
    }

    private fun rememberConnectPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_CONNECT_PORT, port).apply()
    }

    private fun savedHost(context: Context): String? = prefs(context).getString(KEY_HOST, null)

    private fun savedPairPort(context: Context): Int = prefs(context).getInt(KEY_PAIR_PORT, 0)

    private fun savedConnectPort(context: Context): Int = prefs(context).getInt(KEY_CONNECT_PORT, 0)
}
