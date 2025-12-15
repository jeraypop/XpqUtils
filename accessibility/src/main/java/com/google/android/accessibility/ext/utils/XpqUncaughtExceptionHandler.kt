package com.google.android.accessibility.ext.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.accessibility.ext.activity.XpqExceptionReportActivity
import com.android.accessibility.ext.BuildConfig
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appBuildTime
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appMyName
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appVersionCode
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appVersionName
import com.google.android.accessibility.ext.utils.XPQFileUtils.writeStringToFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class XpqUncaughtExceptionHandler private constructor(private val mContext: Context) : Thread.UncaughtExceptionHandler {
    companion object {
        private var instance: XpqUncaughtExceptionHandler? = null

        fun getInstance(context: Context): XpqUncaughtExceptionHandler {
            if (instance == null) {
                instance = XpqUncaughtExceptionHandler(context)
            }
            return instance!!
        }
    }

    fun run() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        handleUiThreadException()
    }

    private fun handleUiThreadException() {
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (e: Throwable) {
                    createExceptionNotification(e)
                    //UMCrash.generateCustomLog(e, e.javaClass.simpleName)
                    e.printStackTrace()
                }
            }
        }
    }

    override fun uncaughtException(thread: Thread, e: Throwable) {
        createExceptionNotification(e)
        //UMCrash.generateCustomLog(e, e.javaClass.simpleName)
        e.printStackTrace()
    }



    private fun getHostAppIcon(): Int {
        return try {
            val packageManager = mContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(mContext.packageName, 0)
            applicationInfo.icon
        } catch (e: PackageManager.NameNotFoundException) {
            // 如果找不到，则使用默认图标
            android.R.drawable.ic_dialog_alert
        }
    }


    fun createExceptionNotification(throwable: Throwable) {
        val err_msg = throwable.message ?: ""
        //Log.e("err_msg", "==$err_msg")
        if (err_msg.contains("Permission denied")) {
            //return
        }

        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)

        val date = java.util.Date(appBuildTime)
        val formateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(date)


        val message = buildString {
            append(Build.FINGERPRINT).append('\n')
            append("BuildTime: ").append(formateTime).append('\n')
            append("AppName: ").append(appMyName).append('\n')
            append("VersionName: ").append(appVersionName).append('\n')
            append("VersionCode: ").append(appVersionCode).append('\n')
            append(stringWriter)
        }


        try {
            val file = File(mContext.filesDir, "exception.txt")
            writeStringToFile(file, message, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            e.printStackTrace()

        }

        val notificationManager = mContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(mContext, XpqExceptionReportActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        //intent.putExtra(Intent.EXTRA_TEXT, message)
        val builder = Notification.Builder(mContext)
            .setContentIntent(PendingIntent.getActivity(mContext, 0x01, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            //这一项不可省略
            .setSmallIcon(getHostAppIcon())
            .setContentTitle("软件运行偶现异常")
            .setContentText(throwable.javaClass.simpleName + ": " + err_msg)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(mContext.packageName)
            val channel = NotificationChannel(mContext.packageName, appMyName, NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
        notificationManager.notify(mContext.packageName, 0x01, builder.build())
    }
}