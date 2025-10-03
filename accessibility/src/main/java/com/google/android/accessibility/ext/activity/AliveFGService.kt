package com.google.android.accessibility.ext.activity


import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.utils.AliveUtils


/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2024/7/15 0015  9:25
 * Description:This is ForegroundService
 */
class AliveFGService : Service() {
    // 服务生命周期说明：
    // 1. onCreate()：服务首次创建时调用
    // 2. onStartCommand()：每次启动服务时调用
    // 3. onBind()：当组件绑定到服务时调用
    // 4. onUnbind()：当所有客户端与服务解除绑定时调用
    // 5. onDestroy()：服务销毁时调用，用于清理资源
    // 该服务通过前台通知实现保活机制，在onCreate中启用，在onDestroy中释放资源

    /*
    *
    *
    *  初始化内容                   建议初始化方法
一次性资源初始化（如通知、悬浮窗等）    ✅ onCreate()
每次启动服务都需要更新的状态           ✅ onStartCommand()
绑定服务相关逻辑（如 AIDL、Binder）    ✅ onBind()
注册广播接收器或监听器                 ✅ onCreate()
全局服务实例引用（如 fg_instance）     ✅ onCreate()
    *
    *
    *
    *
    * */

    companion object{
        private const val TAG = "ForegroundService"
        @JvmField
        var fgs_ison: Boolean = false

        /**
         * 全局服务实例
         * 用于在应用中获取 AliveFGService 服务实例
         * 当服务未启动或被销毁时为null
         *
         * 这段代码声明了一个名为 instance 的可变变量，类型为 AliveFGService?（可空类型），
         * 并将其 setter 设为私有，表示外部无法直接修改该变量值。
         * 作用：实现一个私有可变、外部只读的单例引用。
         *
         */
        var fg_instance: AliveFGService? = null
            private set

    }

     /*
     * 服务首次创建时调用
     * */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG,"OnCreate")
        fg_instance = this
//        AliveUtils.keepAliveByNotification_CLS(this,true,QuanXianActivity::class.java)



     }

    /*
    * 调用时机：当其他组件（如 Activity）通过 bindService() 绑定到该服务时调用
    * */
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

    /*
    * 会被多次调用（每次 startService() 都会触发）
      容易造成重复初始化或资源冲突
    * */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG,"onStartCommand")
        fg_instance = this
        //数据获取
        val data: String? = intent?.getStringExtra("Foreground") ?: "正在开启,请稍等"
        val component = intent?.component
        val activityClassName = component?.className // 获取目标 Activity 的完整类名

        AliveUtils.keepAliveByNotification_CLS(this,true,null)
        AliveUtils.keepAliveByFloatingWindow(this,AliveUtils.getKeepAliveByFloatingWindow())


        return  START_STICKY
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        fg_instance = null
        //取消前台保活服务
        AliveUtils.keepAliveByNotification_CLS(this,false,null)
        AliveUtils.keepAliveByFloatingWindow(this,false)

    }

}