package com.google.android.accessibility.ext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import androidx.core.app.NotificationCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/2/8  10:34
 * Description:This is AlarmForegroundService
 */
class AlarmForegroundService : Service() {
    companion object {
        const val NOTIFICATION_ID = 0xA11
        @JvmOverloads
        @JvmStatic
        fun buildNotification(context: Context = appContext): Notification {
            val channelId = "alarm_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "闹钟执行中",
                    NotificationManager.IMPORTANCE_HIGH
                )
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }

            return NotificationCompat.Builder(context, channelId)
                //图标不可省略,否则会显示为默认格式
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle("定时任务执行中")
                .setContentText("请稍候…")
                .setOngoing(true)
                .build()
        }
        @JvmOverloads
        @JvmStatic
        fun startAlarmService(service: Service?) {
            // api >= 34
            if (Build.VERSION.SDK_INT >= 34) {
                service?.startForeground(NOTIFICATION_ID, buildNotification(), FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
            // api <= 33
            else {
                service?.startForeground(NOTIFICATION_ID, buildNotification())
            }

        }

        @JvmOverloads
        @JvmStatic
        fun stopAlarmService(service: Service?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service?.stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service?.stopForeground(true)
            }

        }


    }

    override fun onCreate() {
        super.onCreate()
        startAlarmService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 执行业务
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null



}
