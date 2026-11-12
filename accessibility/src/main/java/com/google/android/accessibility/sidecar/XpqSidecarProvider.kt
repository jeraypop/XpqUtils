package com.google.android.accessibility.sidecar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.google.android.accessibility.ext.shell.XpqSidecar

/**
 * 内置 ADB 模式下，shell 侧 Sidecar 进程把自身 binder 交回 App 的入口。
 *
 * Sidecar 以 shell 身份调用 [call]（method = attach_sidecar），把 binder 放进 Bundle；
 * 这里只接受 shell / root / 自身进程，避免任意应用塞一个假通道。
 *
 * 注意：清单里必须声明（authority = `${applicationId}.xpq.sidecar`，exported = true）。
 */
class XpqSidecarProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != SidecarProtocol.METHOD_ATTACH) return null
        val callerUid = Binder.getCallingUid()
        val allowed = callerUid == SHELL_UID || callerUid == ROOT_UID || callerUid == Process.myUid()
        val binder = if (allowed) extras?.getBinder(SidecarProtocol.EXTRA_BINDER) else null
        if (binder != null) {
            XpqSidecar.onAttached(binder, extras?.getInt(SidecarProtocol.EXTRA_UID, -1) ?: -1)
        }
        return Bundle().apply {
            putBoolean("ok", binder != null)
            putInt("uid", Process.myUid())
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
