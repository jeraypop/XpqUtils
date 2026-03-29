package com.google.android.accessibility.ext.utils

import android.content.Context

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/3/27  16:57
 * Description:This is SdkInitManager
 */
object SdkInitManager {

    @Volatile
    private var initialized = false

    fun initIfNeeded(context: Context) {
        if (initialized) return

        synchronized(this) {
            if (initialized) return

            if (!isMainProcess(context)) return

            doInit(context.applicationContext)

            initialized = true
        }
    }

    private fun doInit(context: Context) {

        // ⭐ 初始化时间系统（轻量）
        HYSJTimeSecurityManager.init(context)

        // ⭐ 延迟执行（避免影响冷启动）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({

            NetworkHelperFullSmart.registerNetworkListener(context)

            // ⭐ 主动触发一次同步
            NetworkHelperFullSmart.updateMyTime()

        }, 1500)
    }

    private fun isMainProcess(context: Context): Boolean {
        val pid = android.os.Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val processName = am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        return processName == context.packageName
    }
}