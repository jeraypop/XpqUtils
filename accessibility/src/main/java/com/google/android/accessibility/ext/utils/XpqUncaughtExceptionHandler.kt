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
import androidx.core.app.NotificationCompat
import com.google.android.accessibility.ext.activity.XpqExceptionReportActivity
import com.android.accessibility.ext.BuildConfig
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appBuildTime
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appMyName
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appVersionCode
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appVersionName
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.getHostAppIcon
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.sendNotification
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

        val intent = Intent(mContext, XpqExceptionReportActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        //1️⃣ 点击通知 → 打开某个 Activity
        val pendingIntent = PendingIntent.getActivity(
            mContext,
            0x01,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        //2️⃣ 点击通知 → 发送广播
    /*    val intent = Intent("com.xxx.ACTION_CODE_CLICK").apply {
            putExtra("code", code)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )*/

        //3️⃣ 点击通知 → 启动 Service（进阶）
  /*      val intent = Intent(context, CodeHandleService::class.java).apply {
            putExtra("code", code)
        }

        val pendingIntent = PendingIntent.getService(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )*/


        sendNotification(title = "软件运行偶现异常",
            content = throwable.javaClass.simpleName + ": " + err_msg,
            pendingIntent = pendingIntent)

    }
}