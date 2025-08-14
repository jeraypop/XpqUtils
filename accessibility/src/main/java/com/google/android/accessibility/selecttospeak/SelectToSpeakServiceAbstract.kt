package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.ext.AssistsServiceListener
import com.google.android.accessibility.ext.toast
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.MMKVConst.AUTOBAOHUOISON
import com.google.android.accessibility.ext.utils.SPUtils
import com.google.android.accessibility.ext.utils.SPUtils.getBoolean
import com.google.android.accessibility.ext.window.AssistsWindowManager
import java.util.Collections
import java.util.concurrent.Executors

abstract class SelectToSpeakServiceAbstract : AccessibilityService() {
    private val TAG = this::class.java.simpleName

    private val executors = Executors.newSingleThreadExecutor()

    abstract fun targetPackageName(): String

    abstract fun asyncHandleAccessibilityEvent(event: AccessibilityEvent)

    override fun onServiceConnected() {
        toast("11")
        instance = this
        AssistsWindowManager.init(this)
        Log.d(TAG, "onServiceConnected: ")
        runCatching { listeners.forEach { it.onServiceConnected(this) } }

        if (AliveUtils.getKeepAliveByNotification()){
            //前台保活服务   如果放在子类中 可传入 class了
            AliveUtils.keepAliveByNotification_CLS(this,true,null)
        }
        AliveUtils.keepAliveByFloatingWindow(this,AliveUtils.getKeepAliveByFloatingWindow(),true)

    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val accessibilityEvent = event ?: return
        instance = this
        executors.run { asyncHandleAccessibilityEvent(accessibilityEvent) }
        runCatching { listeners.forEach { it.onAccessibilityEvent(event) } }
    }
    /**
     * 服务解绑时调用
     * 清除服务实例并通知所有监听器
     * @param intent 解绑的Intent
     * @return 是否调用父类的onUnbind方法
     */
    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { listeners.forEach { it.onUnbind() } }
        return super.onUnbind(intent)
    }
    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt: ")
        runCatching { listeners.forEach { it.onInterrupt() } }
    }



    override fun onDestroy() {
        instance = null
        if (AliveUtils.getKeepAliveByNotification()){
            //前台保活服务   如果放在子类中 可传入 class了
            AliveUtils.keepAliveByNotification_CLS(this,false,null)
        }
        AliveUtils.keepAliveByFloatingWindow(this,false,true)
        super.onDestroy()
    }

    companion object {
        /**
         * 全局服务实例
         * 用于在应用中获取无障碍服务实例
         * 当服务未启动或被销毁时为null
         *
         * 这段代码声明了一个名为 instance 的可变变量，类型为 SelectToSpeakServiceAbstract?（可空类型），
         * 并将其 setter 设为私有，表示外部无法直接修改该变量值。
         * 作用：实现一个私有可变、外部只读的单例引用。
         *
         */
        var instance: SelectToSpeakServiceAbstract? = null
            private set
        /**
         * 服务监听器列表
         * 使用线程安全的集合存储所有监听器
         * 用于分发服务生命周期和无障碍事件
         */
        val listeners: MutableList<AssistsServiceListener> = Collections.synchronizedList(arrayListOf<AssistsServiceListener>())
    }
}