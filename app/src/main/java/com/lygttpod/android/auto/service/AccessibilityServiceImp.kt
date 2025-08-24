package com.lygttpod.android.auto.service

//import com.assistant.`fun`.redbwx.utils.MyUtilsKotlin.zhuli_youSkipAD
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.utils.AliveUtils.toast

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
        toast(applicationContext,"33")
        //=========================

    }



    @RequiresApi(Build.VERSION_CODES.N)
    override fun asyncHandleAccessibilityEvent(accessibilityEvent: AccessibilityEvent) {
        if (accessibilityEvent==null)return

    }



    override fun onDestroy() {
        super.onDestroy()
    }




}