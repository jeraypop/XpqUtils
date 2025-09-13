package com.lygttpod.android.auto.service

//import com.assistant.`fun`.redbwx.utils.MyUtilsKotlin.zhuli_youSkipAD
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.AliveUtils.toast
import com.google.android.accessibility.notification.AccessibilityNInfo

/**
 * 第二次继承
 *
 *
 *  WXAccessibility
 *
 */
open class AccessibilityServiceImp : FirstAccessibility() {

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return Service.START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)


    }

    override fun onUnbind(intent: Intent?): Boolean {

        return super.onUnbind(intent)
    }
    override fun onInterrupt() {




    }
    @SuppressLint("SuspiciousIndentation")
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onServiceConnected() {
        super.onServiceConnected()
        toast(applicationContext,"无障碍服务已连接")
        //=========================

    }



    @RequiresApi(Build.VERSION_CODES.N)
    override fun asyncHandleAccessibilityEvent(accessibilityEvent: AccessibilityEvent) {
        if (accessibilityEvent==null)return

    }

    override fun asyncHandleAccessibilityNotification(notification: Notification, title: String, content: String, a_n_Info: AccessibilityNInfo){
        val pi = a_n_Info.pi
        AliveUtils.piSend( pi)
        // 统一日志输出，便于对比
        Log.e("辅助服务通知", "===============================\n" +
                "来自应用: ${a_n_Info.pkgName} = ${a_n_Info.appName}\n" +
                "方式一 (event.text): ${a_n_Info.eventText}\n" +
                "方式二 (Notification): 标题=[${a_n_Info.title}] 内容=[${a_n_Info.content}]\n" +
                "===============================")

    }



    override fun onDestroy() {
        super.onDestroy()
    }




}