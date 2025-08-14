package com.lygttpod.android.auto.service

//import com.assistant.`fun`.redbwx.utils.MyUtilsKotlin.zhuli_youSkipAD
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.utils.AliveUtils.toast

import java.util.LinkedList

/**
 * 每一次打开插件设置，都是一条新的service
 *
 *  WXAccessibility
 *  AccessibilityService
 */
open class AccessibilityServiceImp : WXAccessibility() {

    private val tag = "无障碍服务"


    companion object {

         var service: AccessibilityServiceImp? = null
        fun getService(): AccessibilityService? {
            return service
        }

        fun getMyService(): AccessibilityService? {
            return service
        }

        fun isEmpty(): Boolean {

//            val serviceRunning = myUtils!!.isServiceRunning()
//            Log.e("serviceRunning", "isEmpty: "+!serviceRunning)
//            return !serviceRunning

            return service == null


        }



    }


    override fun onCreate() {
        super.onCreate()


    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return Service.START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)


    }

    override fun onUnbind(intent: Intent?): Boolean {


//        service = null
        return super.onUnbind(intent)
    }
    override fun onInterrupt() {




    }
    @SuppressLint("SuspiciousIndentation")
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onServiceConnected() {
        super.onServiceConnected()
        service = this
        toast(applicationContext,"33")

        //=========================


        //=======================启动前台服务

//        if (spwang?.getBoolean("autobaohuoison", false) == true){
//            //前台保活服务
//            keepAliveByNotification_CLS(this,true,null)
//        }

        //=======================启动前台服务
        Log.e("辅助服务状态", "onServiceConnected")


    }



    @RequiresApi(Build.VERSION_CODES.N)
    override fun asyncHandleAccessibilityEvent(accessibilityEvent: AccessibilityEvent) {
        if (accessibilityEvent==null)return

    }



    override fun onDestroy() {

        service = null

        super.onDestroy()
    }




}