package com.google.android.accessibility.ext.shell

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File

/**
 * 用 Kadb（纯 Kotlin ADB 客户端）直连本机 adbd，拿到 shell 身份，不依赖 Shizuku。
 *
 * 前提：系统「开发者选项 → 无线调试」已打开。
 * 首次使用要配对：在同一页面点「使用配对码配对设备」，把显示的端口和 6 位配对码传进来。
 * 私钥持久化在 `filesDir/adb/adbkey.pem`，授权/配对一次后进程重启仍有效。
 */
class KadbShellBridge(
    private val context: Context,
    private val host: String,
    private val port: Int,
) : ShellBridge {

    override val name: String get() = "Kadb($host:$port)"

    @Volatile
    private var client: Kadb? = null

    /** 身份是否已配置（KadbCert 是进程级全局配置，配一次即可）。 */
    @Volatile
    private var identityReady = false

    override var onDisconnected: (() -> Unit)? = null

    override fun isConnected(): Boolean =
        runCatching { client?.connectionCheck() == true }.getOrDefault(false)

    /**
     * 把 Kadb 的身份落到应用私有目录：同一把私钥跨进程复用，
     * 设备上的授权和配对结果因此能保留，不会每次启动都重新弹窗。
     */
    private fun ensureIdentity() {
        if (identityReady) return
        synchronized(this) {
            if (identityReady) return
            val keyFile = File(context.filesDir, "adb/adbkey.pem")
            keyFile.parentFile?.mkdirs()
            KadbCert.configure(
                store = OkioFilePrivateKeyStore(privateKeyPath = keyFile.absolutePath.toPath()),
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList(),
            )
            // 只 configure 不会真正加载/生成身份，必须 ensureReady 才会读写这把持久化私钥
            val snapshot = KadbCert.ensureReady()
            identityReady = true
            Log.i(
                TAG,
                "identity -> ${keyFile.absolutePath} fingerprint=${snapshot.fingerprintSha256.take(16)}"
            )
        }
    }

    /** 最近一次配对失败的真实原因（异常类型 + message），供上层展示。 */
    @Volatile
    var lastPairError: String? = null
        private set

    /** 最近一次配对失败的原始异常，供上层按类型给不同提示（对应 Shizuku 的错误分类）。 */
    @Volatile
    var lastPairThrowable: Throwable? = null
        private set

    /**
     * 配对。阻塞，需在子线程调用。
     * 成功返回 true，之后调 [connect]；失败原因见返回值和 [lastPairError]。
     */
    fun pair(code: String): Boolean {
        ensureIdentity()
        lastPairError = null
        lastPairThrowable = null
        // 配对是单次 TLS/PSK 握手，首次偶尔会因端口刚起、socket 复用等原因失败；
        // 重试一次能明显提高成功率（码错时也只是多失败一次）。
        var last: Throwable? = null
        repeat(PAIR_ATTEMPTS) { attempt ->
            val ok = runCatching {
                runBlocking { Kadb.pair(host, port, code) }
            }.onFailure { last = it }.isSuccess
            if (ok) {
                Log.i(TAG, "paired $name (attempt ${attempt + 1})")
                return true
            }
            Log.w(TAG, "pair attempt ${attempt + 1} failed: ${last?.javaClass?.simpleName}: ${last?.message}")
            if (attempt < PAIR_ATTEMPTS - 1) SystemClock.sleep(300)
        }
        lastPairThrowable = last
        lastPairError = "${last?.javaClass?.simpleName}: ${last?.message}"
        Log.w(TAG, "pair failed $name: $lastPairError", last)
        return false
    }

    /** 最近一次连接失败的真实原因（异常类型 + message），供上层展示。 */
    @Volatile
    var lastConnectError: String? = null
        private set

    override fun connect(): Boolean {
        if (isConnected()) return true
        return runCatching {
            ensureIdentity()
            val c = Kadb.create(host, port)
            // 用 id 确认拿到的确实是 shell 身份，而不是普通用户
            val id = c.shell("id")
            if (id.exitCode != 0 || !id.output.contains("uid=2000")) {
                Log.w(TAG, "connected but not shell: exit=${id.exitCode} out=${id.output.trim()}")
                runCatching { c.close() }
                lastConnectError = "连上了但不是 shell 身份（exit=${id.exitCode}）"
                return false
            }
            client = c
            lastConnectError = null
            Log.i(TAG, "connected $name -> ${id.output.trim()}")
            true
        }.onFailure {
            lastConnectError = "${it.javaClass.simpleName}: ${it.message}"
            Log.w(TAG, "connect failed $name: $lastConnectError", it)
        }.getOrDefault(false)
    }

    override fun disconnect() {
        runCatching { client?.close() }
        client = null
    }

    /**
     * 让 adbd 切到固定 TCP 端口（等价 `adb tcpip <port>`）。
     *
     * `tcpip:<port>` 是 ADB 的 service 字符串，由 adbd 自己处理并重启自己，
     * 因此不能用 `shell` 执行，只能 `open` 一个原始 service。
     * 调用后当前连接会失效；adbd 起来后再用 `connect()` 连新端口。
     */
    fun switchToTcpPort(port: Int): Boolean {
        val c = client ?: run {
            lastConnectError = "尚未连接，无法切换 TCP 模式"
            return false
        }
        return runCatching {
            val stream = c.open("tcpip:$port")
            runCatching { stream.close() }
            Log.i(TAG, "tcpip:$port accepted")
            true
        }.onFailure {
            lastConnectError = "${it.javaClass.simpleName}: ${it.message}"
            Log.w(TAG, "tcpip:$port failed: $lastConnectError", it)
        }.getOrDefault(false)
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult? {
        val c = client ?: return null
        return runCatching {
            val r = c.shell(command)
            ShellResult(r.exitCode, r.output)
        }.onFailure {
            Log.w(TAG, "exec failed: $command", it)
            // 连接已断：丢掉客户端并通知上层，避免继续往死连接里写
            if (!isConnected()) {
                client = null
                onDisconnected?.invoke()
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "XpqAdbShell"
        const val PAIR_ATTEMPTS = 2
    }
}
