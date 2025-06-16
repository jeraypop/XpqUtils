package com.lygttpod.android.auto


import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi

import com.lygttpod.android.auto.wx.service.MyUtilsKotlin
import com.lygttpod.android.auto.wx.service.WXAccessibility.Companion.service


/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2024/7/15 0015  9:25
 * Description:This is ForegroundService
 */
class ForegroundService14 : Service() {

    companion object{
        @JvmField
        var mForegroundService14:Intent?=null
        private const val TAG = "ForegroundService"
        @JvmField
        var serviceIsLive14: Boolean = false

        private val NOTIFICATION_ID = 0x06
        private val CHANNEL_ID = "稳定运行"
        private val CHANNEL_NAME = "后台稳定运行通知"
        private val CHANNEL_DESCRIPTION = "降低软件后台运行时被杀的概率"


    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG,"OnCreate")
        MyUtilsKotlin.keepAliveByNotification_CLS(this,true,MainActivity::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG,"onBind")
        return null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG,"onUnbind")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG,"onStartCommand")
        serviceIsLive14=true
        //数据获取
        val data: String? = intent?.getStringExtra("Foreground") ?: "正在开启,请稍等"
//        if (MyApplication.spwang?.getBoolean("autobaohuoison", false) == false){
//            Toast.makeText(this, data, Toast.LENGTH_SHORT).show()
//        }

        return  START_STICKY
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        //取消前台保活服务
        MyUtilsKotlin.keepAliveByNotification_CLS(service,false,null)
        serviceIsLive14 = false;
    }

}