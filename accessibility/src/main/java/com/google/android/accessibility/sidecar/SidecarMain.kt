package com.google.android.accessibility.sidecar

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import com.google.android.accessibility.uiautomation.util.HiddenApi
import rikka.hidden.compat.ActivityManagerApis

/**
 * shell 侧常驻进程入口（由内置 ADB 以 `app_process` 拉起，uid 2000）。
 *
 * 只做一件事：把自己的 binder 通过宿主的 ContentProvider 交给 App 进程，然后等着处理
 * [SidecarProtocol.TX_TRANSACT]（以 shell 身份转发系统服务调用）和 [SidecarProtocol.TX_EXEC]。
 *
 * 启动命令（由 [com.google.android.accessibility.ext.shell.XpqSidecar] 生成）：
 * ```
 * CLASSPATH=<base.apk> app_process /system/bin --nice-name=xpq_sidecar \
 *     com.google.android.accessibility.sidecar.SidecarMain <authority> <userId> <hostPkg>
 * ```
 */
object SidecarMain {
    private const val TAG = "XpqSidecar"

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val authority = args.getOrNull(0)
        val userId = args.getOrNull(1)?.toIntOrNull() ?: 0
        val hostPackage = args.getOrNull(2)
        if (authority.isNullOrBlank()) {
            Log.e(TAG, "缺少 provider authority，退出")
            return
        }
        runCatching { HiddenApi.ensure() }
        val handler = Handler(Looper.getMainLooper())
        val service = SidecarService()

        fun tryAttach() {
            val ok = runCatching { attach(authority, userId, hostPackage, service) }
                .onFailure { Log.w(TAG, "attach 失败：${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            if (!ok) {
                handler.postDelayed({ tryAttach() }, 1_000L)
            }
        }

        handler.post { tryAttach() }
        Looper.loop()
    }

    /** 把 binder 交给宿主；失败返回 false（调用方会重试，宿主可能还没起来）。 */
    private fun attach(
        authority: String,
        userId: Int,
        hostPackage: String?,
        service: IBinder,
    ): Boolean {
        val token = Binder()
        // compat 的 getContentProviderExternal 在不同版本可能返回 holder，也可能直接返回 provider 代理，
        // 两种都兼容：能取到 provider 字段就用字段，否则把返回值本身当 provider。
        val raw: Any = ActivityManagerApis.getContentProviderExternal(
            authority, userId, token, hostPackage
        ) ?: run {
            Log.w(TAG, "拿不到 provider：$authority")
            return false
        }
        val provider: Any = runCatching {
            raw.javaClass.getField("provider").get(raw)
        }.getOrNull() ?: raw

        // 进程可能先于 App 死亡：provider binder 一死就自退出，避免留下孤儿进程
        runCatching {
            val providerBinder = provider.javaClass.getMethod("asBinder").invoke(provider) as IBinder
            providerBinder.linkToDeath({ Process.killProcess(Process.myPid()) }, 0)
        }

        val extras = Bundle().apply {
            putBinder(SidecarProtocol.EXTRA_BINDER, service)
            putInt(SidecarProtocol.EXTRA_UID, Process.myUid())
        }
        val result = callProvider(
            provider = provider,
            callingPackage = hostPackage ?: "com.android.shell",
            authority = authority,
            method = SidecarProtocol.METHOD_ATTACH,
            arg = null,
            extras = extras,
        )
        val ok = result?.getBoolean("ok") == true
        Log.i(TAG, "attach $authority -> $ok (uid=${Process.myUid()}, pid=${Process.myPid()})")
        return ok
    }

    /**
     * 调 `IContentProvider.call(...)`。参数表随 Android 版本变化，逐个尝试：
     *  - 13+ : call(AttributionSource, authority, method, arg, extras)
     *  - 11  : call(callingPkg, attributionTag, authority, method, arg, extras)
     *  - 10  : call(callingPkg, authority, method, arg, extras)
     *  - 9-  : call(callingPkg, method, arg, extras)
     */
    private fun callProvider(
        provider: Any,
        callingPackage: String,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val methods = provider.javaClass.methods.filter { it.name == "call" }

        // 13+
        methods.firstOrNull {
            it.parameterCount == 5 &&
                    it.parameterTypes[0].name == "android.content.AttributionSource"
        }?.let { m ->
            val attribution = runCatching {
                val builderCls = Class.forName("android.content.AttributionSource\$Builder")
                val builder = builderCls
                    .getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(Process.myUid())
                builderCls.getMethod("setPackageName", String::class.java)
                    .invoke(builder, callingPackage)
                builderCls.getMethod("build").invoke(builder)
            }.getOrElse { return@let }
            return m.invoke(provider, attribution, authority, method, arg, extras) as? Bundle
        }

        // 11
        methods.firstOrNull {
            it.parameterCount == 6 &&
                    it.parameterTypes.all { p -> p == String::class.java || p == Bundle::class.java }
        }?.let { m ->
            return m.invoke(provider, callingPackage, null, authority, method, arg, extras) as? Bundle
        }

        // 10
        methods.firstOrNull {
            it.parameterCount == 5 &&
                    it.parameterTypes[0] == String::class.java
        }?.let { m ->
            return m.invoke(provider, callingPackage, authority, method, arg, extras) as? Bundle
        }

        // 9-
        methods.firstOrNull { it.parameterCount == 4 }?.let { m ->
            return m.invoke(provider, callingPackage, method, arg, extras) as? Bundle
        }

        Log.w(TAG, "找不到可用的 IContentProvider.call 重载")
        return null
    }

    /** Sidecar 对外暴露的 binder。 */
    class SidecarService : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                SidecarProtocol.TX_PING -> {
                    data.enforceInterface(SidecarProtocol.DESCRIPTOR)
                    reply?.writeInt(Process.myUid())
                    reply?.writeInt(Process.myPid())
                    return true
                }

                SidecarProtocol.TX_TRANSACT -> {
                    data.enforceInterface(SidecarProtocol.DESCRIPTOR)
                    val target = data.readStrongBinder() ?: return false
                    val origCode = data.readInt()
                    val origFlags = data.readInt()
                    // binder 发送的是整段 Parcel（从 0 开始），我们的协议头必须先剥掉：
                    // 把剩余内容拷到新 Parcel 再转发，目标服务才能看到自己的 interface token。
                    val start = data.dataPosition()
                    val payload = Parcel.obtain()
                    return try {
                        payload.appendFrom(data, start, data.dataSize() - start)
                        target.transact(origCode, payload, reply, origFlags)
                    } finally {
                        payload.recycle()
                    }
                }

                SidecarProtocol.TX_EXEC -> {
                    data.enforceInterface(SidecarProtocol.DESCRIPTOR)
                    val command = data.readString() ?: ""
                    val process = ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                    val output = runCatching {
                        process.inputStream.bufferedReader().use { it.readText() }
                    }.getOrDefault("")
                    val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                    reply?.writeInt(exitCode)
                    reply?.writeString(output)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
