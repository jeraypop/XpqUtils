package com.google.android.accessibility.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.accessibility.baoshi.TimeReportScheduler
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.SdkInitManager

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
                SdkInitManager.initIfNeeded(context)
                if (AliveUtils.getKeepAliveByNotification()){
                    AliveUtils.startFGAlive(enable = true)
                }
                 //报时
                //TimeReportScheduler.schedule(context)
                // 内置 ADB 模式：开机后用持久化的配对密钥 + mDNS 自动端口重连，并拉起 Sidecar，
                // 做到和 Shizuku 一样「重启后自动恢复通道」。
                // 只在 BOOT_COMPLETED（已解锁、能读凭据存储）时做，LOCKED_BOOT 阶段 MMKV 还不可用。
                if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
                    // 「自启动」关闭时不自动恢复（对应高级设置里的开关）
                    if (com.google.android.accessibility.ext.shell.XpqAdbShell.isAutoStartEnabled(context)) {
                        com.google.android.accessibility.ext.shell.XpqAdbShell.autoConnect(context)
                    }
                    // 看门狗独立于「自启动」：开着就重新拉起前台服务（与 Shizuku 一致）
                    com.google.android.accessibility.ext.shell.XpqAdbShell.startWatchdog(context)
                }
            }

            Intent.ACTION_SHUTDOWN -> {
                // 关机时的处理逻辑
                AliveUtils.startFGAlive(enable = false)
            }
        }
    }
}

