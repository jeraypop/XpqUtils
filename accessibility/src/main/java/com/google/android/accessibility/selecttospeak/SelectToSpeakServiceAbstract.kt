package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

import com.google.android.accessibility.ext.AssistsServiceListener
import com.google.android.accessibility.ext.window.AssistsWindowManager
import java.util.Collections
import java.util.concurrent.Executors

abstract class SelectToSpeakServiceAbstract : AccessibilityService() {
    private val TAG = this::class.java.simpleName

    private val executors = Executors.newSingleThreadExecutor()

    abstract fun targetPackageName(): String

    abstract fun asyncHandleAccessibilityEvent(event: AccessibilityEvent)

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

    override fun onServiceConnected() {
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
        instance = this
        AssistsWindowManager.init(this)
        Log.d(TAG, "onServiceConnected: ")
        runCatching { listeners.forEach { it.onServiceConnected(this) } }
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "onDestroy: ")
        super.onDestroy()
    }

    companion object {
        /**
         * 全局服务实例
         * 用于在应用中获取无障碍服务实例
         * 当服务未启动或被销毁时为null
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