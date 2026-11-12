package com.google.android.accessibility.sidecar

/**
 * App 进程与 shell 侧 Sidecar 进程之间的私有协议。
 *
 * Sidecar 由内置 ADB 以 shell 身份（uid 2000）用 app_process 拉起，负责做「App 做不了、
 * 必须 shell 身份才能做」的两件事：
 *  1. [TX_TRANSACT]：把 App 的 binder 事务以 shell 身份转发给系统服务（等价 ShizukuBinderWrapper）；
 *  2. [TX_EXEC]：以 shell 身份执行命令（等价 Shizuku.newProcess）。
 *
 * 原始 binder 通过 App 内的 ContentProvider 交接：Sidecar 启动后调用
 * `IActivityManager.getContentProviderExternal` 拿到宿主 provider，再 `call()` 把自身 binder 放进 Bundle。
 * 这样走的是 binder 事务，binder 对象由驱动转交，不需要自己处理 socket fd。
 */
internal object SidecarProtocol {
    /** 自定义 binder 接口标识。 */
    const val DESCRIPTOR = "com.google.android.accessibility.sidecar.ISidecar"

    /** ping：回 uid/pid，用于确认进程确实以 shell 身份跑起来。 */
    const val TX_PING = 1

    /** 转发事务：writeStrongBinder(target) + int code + int flags + 原始 data 内容。 */
    const val TX_TRANSACT = 2

    /** 执行命令：readString(cmd)；回 int exitCode + String stdout。 */
    const val TX_EXEC = 3

    /** Sidecar 拉起时用的进程名，用于清理旧进程。 */
    const val PROCESS_NAME = "xpq_sidecar"

    /** ContentProvider 上接收 Sidecar binder 的方法名。 */
    const val METHOD_ATTACH = "attach_sidecar"

    /** Bundle key：Sidecar 服务 binder。 */
    const val EXTRA_BINDER = "sidecar_binder"

    /** Bundle key：Sidecar 的 uid（应为 2000）。 */
    const val EXTRA_UID = "sidecar_uid"
}
