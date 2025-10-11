package com.google.android.accessibility.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.accessibility.ext.utils.AliveUtils

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/10/2  16:46
 * Description:This is BootReceiver
 *
 * 关机广播不能唤醒应用，只有应用之前运行过、进程还活着时才会收到。
 *
 * 它在 Android 3.1+ 就被限制了：应用必须先被用户至少启动过一次，才能在之后接收关机广播。
 *
 * 不能在关机广播里启动 Activity（因为系统正在关机），但你可以保存数据、停止服务等。
 *
 */
class XpqBootReceiver : BroadcastReceiver() {
    companion object {

        var permissionTempValue: Boolean = false
        private var lastExecuteTime: Long = 0
        private const val INTERVAL: Long = 5000 // 5秒
    }
    override fun onReceive(context: Context, intent: Intent?) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastExecuteTime < INTERVAL) {
            return // 5秒内不重复执行
        }
        lastExecuteTime = currentTime
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // 开机启动服务

                if (AliveUtils.getKeepAliveByNotification()){
                    AliveUtils.startFGAlive(enable = true)
                }


            }

            Intent.ACTION_SHUTDOWN -> {
                // 关机时的处理逻辑
                AliveUtils.startFGAlive(enable = false)
            }
        }
    }
}

