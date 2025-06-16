package com.lygttpod.android.auto.wx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import androidx.annotation.RequiresApi
import com.lygttpod.android.auto.wx.R
import kotlin.math.max

object MyUtilsKotlin {
    @RequiresApi(Build.VERSION_CODES.M)
    @JvmStatic
    fun keepAliveByNotification_CLS(service: Service?, enable: Boolean, intentCla: Class<*>?) {
        if (service == null) {
            return
        }
        val NOTIFICATION_ID = 0x06
        val CHANNEL_ID = "稳定运行"
        val CHANNEL_NAME = "后台稳定运行通知"
        val CHANNEL_DESCRIPTION = "降低软件后台运行时被杀的概率"

        if (enable) {
            val notificationManager = service?.getSystemService(
                NotificationManager::class.java
            )



            //创建通知
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(service,CHANNEL_ID)
            } else {
                Notification.Builder(service)
            }
            builder.setOngoing(true)
            builder.setAutoCancel(false)
            builder.setSmallIcon(R.drawable.ic_launcherplaystore)
//            builder.setContentTitle("后台稳定运行通知")
            builder.setContentTitle(CHANNEL_NAME)
            //通知内容
            builder.setContentText("更多好玩软件尽在公众号:消屏器")
            if (intentCla!=null){
                val intent = Intent(service, intentCla)
                val pendingIntent: PendingIntent
                pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_MUTABLE)
                } else {
                    PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                }
                builder.setContentIntent(pendingIntent)
            }


            //创建通知渠道
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(CHANNEL_ID)
                val channel = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESCRIPTION
                }
                notificationManager?.createNotificationChannel(channel)
            }

            // api >= 34
            if (Build.VERSION.SDK_INT >= 34) {
                service?.startForeground(NOTIFICATION_ID, builder.build(), FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

            }
            // api <= 33
            else {
                service?.startForeground(NOTIFICATION_ID, builder.build())
            }

        } else {

            try {
                // 尝试停止前台服务，并添加日志记录以追踪此操作
                if (Build.VERSION.SDK_INT >= 26) {
                    service?.stopForeground(STOP_FOREGROUND_REMOVE)
                }else{
                    service?.stopForeground(true)
                }
            }  catch (e: Exception) {

            }



        }
    }
    @JvmStatic
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val parts2 = v2.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        val maxLength = max(parts1.size.toDouble(), parts2.size.toDouble()).toInt()
        for (i in 0 until maxLength) {
            val num1 = if (i < parts1.size) parts1[i].toInt() else 0
            val num2 = if (i < parts2.size) parts2[i].toInt() else 0

            if (num1 != num2) {
                return Integer.compare(num1, num2)
            }
        }
        return 0
    }

}