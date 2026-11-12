package com.google.android.accessibility.ext.shell

/**
 * shell 身份（uid 2000）命令通道的统一抽象。
 *
 * 现有/规划的几种实现都挂在同一接口下，上层（虚拟屏、输入注入、系统命令）不感知通道来源：
 *  - [KadbShellBridge]：内置 ADB，走系统「无线调试」配对后直连本机 adbd，不依赖 Shizuku
 *  - 后续可加：Shizuku 通道、Root 通道
 */
interface ShellBridge {
    /** 实现名，写日志用。 */
    val name: String

    /** 通道当前是否可用。 */
    fun isConnected(): Boolean

    /** 建立通道并校验身份（必须是 shell）。阻塞，需在子线程调用。 */
    fun connect(): Boolean

    /** 断开并释放。 */
    fun disconnect()

    /**
     * 执行一条 shell 命令。
     * 未连接、执行异常或超时返回 null；命令本身失败通过 [ShellResult.exitCode] 体现。
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult?

    /** 通道被动断开时回调，上层可据此重连或回退其它通道。 */
    var onDisconnected: (() -> Unit)?

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

/** 一条 shell 命令的执行结果。 */
data class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}
