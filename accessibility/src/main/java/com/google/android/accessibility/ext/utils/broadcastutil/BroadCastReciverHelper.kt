package com.google.android.accessibility.ext.utils.broadcastutil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.google.android.accessibility.ext.utils.LibCtxProvider
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.util.Collections
import java.util.WeakHashMap

/**
 * 安全广播注册 / 反注册底座
 * - 防止重复注册
 * - 防止 unregisterReceiver 崩溃
 * - 支持 Android 13+ exported flag
 */
object BroadCastReciverHelper {

    private val registeredReceivers =
        Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<BroadcastReceiver, Boolean>())
        )


    @JvmStatic
    fun isRegistered(receiver: BroadcastReceiver?): Boolean {
        return receiver != null && registeredReceivers.contains(receiver)
    }

    private fun markRegistered(receiver: BroadcastReceiver) {
        registeredReceivers.add(receiver)
    }

    private fun markUnregistered(receiver: BroadcastReceiver) {
        registeredReceivers.remove(receiver)
    }

    @JvmStatic
    @JvmOverloads
    fun safeRegisterReceiver(
        context: Context? = appContext,
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        exported: Boolean = true
    ): Boolean {
        if (context == null || receiver == null || filter == null) return false
        if (isRegistered(receiver)) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 14+ 需要指定标志
                //RECEIVER_NOT_EXPORTED 标志表示广播接收器不能接收来自其他应用的广播，只能接收来自同一应用或系统发送的广播。
                //相当于静态注册时的 exported=false
                context.registerReceiver(receiver, filter,
                    if(exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                // Android 14 以下版本
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            markRegistered(receiver)
            true
        } catch (_: Throwable) {
            // 注册失败，不记录状态
            false
        }
    }

    @JvmStatic
    @JvmOverloads
    fun safeUnregisterReceiver(context: Context? = appContext, receiver: BroadcastReceiver?) {
        if (context == null || receiver ==null) return
        if (!isRegistered(receiver)) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {

        }finally {
            markUnregistered(receiver)
        }
    }

}