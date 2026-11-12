package com.google.android.accessibility.ext.shell

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import com.google.android.accessibility.sidecar.SidecarProtocol
import java.io.FileDescriptor

/**
 * 与 `ShizukuBinderWrapper` 等价的包装器：把对 [original] 的每一次 transact 交给 Sidecar，
 * 由 shell 身份的 Sidecar 进程转发给系统服务。
 *
 * 请求格式（Sidecar 侧按同样顺序解包）：
 * ```
 * writeInterfaceToken(DESCRIPTOR)
 * writeStrongBinder(original)
 * writeInt(code)
 * writeInt(flags)
 * appendFrom(原 data)
 * ```
 * reply 直接复用调用方的 Parcel，因此目标服务的返回值原样带回。
 */
class SidecarBinderWrapper(private val original: IBinder) : IBinder {

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val sidecar = XpqSidecar.binder() ?: throw RemoteException("内置 ADB 的 Sidecar 未连接")
        val newData = Parcel.obtain()
        try {
            newData.writeInterfaceToken(SidecarProtocol.DESCRIPTOR)
            newData.writeStrongBinder(original)
            newData.writeInt(code)
            newData.writeInt(flags)
            newData.appendFrom(data, 0, data.dataSize())
            return sidecar.transact(SidecarProtocol.TX_TRANSACT, newData, reply, 0)
        } finally {
            newData.recycle()
        }
    }

    override fun getInterfaceDescriptor(): String? = original.interfaceDescriptor

    override fun pingBinder(): Boolean = original.pingBinder()

    override fun isBinderAlive(): Boolean = original.isBinderAlive()

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun dump(fd: FileDescriptor, args: Array<out String>?) = original.dump(fd, args)

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = original.dumpAsync(fd, args)

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) =
        original.linkToDeath(recipient, flags)

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
        original.unlinkToDeath(recipient, flags)
}
