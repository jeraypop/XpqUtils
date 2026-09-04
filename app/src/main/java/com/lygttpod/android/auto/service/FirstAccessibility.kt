package com.lygttpod.android.auto.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent


import com.google.android.accessibility.notification.AccessibilityNInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.google.android.accessibility.selecttospeak.XPQEventData
import com.google.android.accessibility.selecttospeak.accessibilityService
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.broadcastutil.BroadcastOwnerType
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateCallback
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateReceiver
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager.CHANNEL_SCREEN
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager.screenFilter
import java.util.concurrent.Executors

//import com.lygttpod.android.auto.wx.helper.ToastUtil.keepAliveByNotification_CLS
import java.util.concurrent.atomic.AtomicBoolean


 /*
 * 第一次继承
 *
 * */
open class FirstAccessibility : SelectToSpeakServiceAbstract() {

    companion object {
        var isInWXApp = AtomicBoolean(false)
    }

     // 自己的线程池处理长耗时任务（可以是上传、复杂解析、长时间点击重试等）
     private val worker = Executors.newFixedThreadPool(2)

    override fun targetPackageName() = "com.tencent.mm"



    override fun onServiceConnected() {
        super.onServiceConnected()
        //7/0
    }

    // UiAutomation 模式下的屏幕状态回调（强引用，避免 ScreenStateReceiver 的 WeakReference 导致被 GC 收不到回调）
    private var uiAutomationScreenCallback: ScreenStateCallback? = null

    override fun onUiAutomationReady(service: AccessibilityService?) {
        super.onUiAutomationReady(service)
        // 手动 new 的实例未经系统 attach、Context 受限，屏幕广播改用 appContext 注册
        val callback = object : ScreenStateCallback {
            override fun onScreenOff() { Log.e("监听屏幕啊", "UiAutomation屏幕已关闭") }
            override fun onScreenOn() { Log.e("监听屏幕啊", "UiAutomation屏幕点亮") }
            override fun onUserPresent() { Log.e("监听屏幕啊", "UiAutomation真正解锁完成") }
        }
        uiAutomationScreenCallback = callback
        UnifiedBroadcastManager.register(
            channel = CHANNEL_SCREEN,
            owner = this,
            ownerType = BroadcastOwnerType.ACCESSIBILITY_SERVICE,
            context = appContext,
            receiver = ScreenStateReceiver(callback),
            filter = screenFilter
        )
    }

    override fun onUiAutomationDestroy() {
        super.onUiAutomationDestroy()
        // 释放屏幕广播 + 清强引用
        UnifiedBroadcastManager.unregister(
            channel = CHANNEL_SCREEN,
            owner = this,
            context = appContext
        )
        uiAutomationScreenCallback = null
        worker.shutdownNow()
    }

    override fun asyncHandleAccessibilityEvent(event: AccessibilityEvent) {
//        HBTaskHelper.hbTask(event)
        //val s = getTextById(this, "com.tencent.mm:id/obn")



        
    }

     override fun asyncHandle_WINDOW_STATE_CHANGED(
         eventData: XPQEventData
     ) {
         // 场景：我们希望对每个节点做一个长耗时操作并在完成后释放副本
         eventData.pkgName

     }



     override fun onInterrupt() {
        super.onInterrupt()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }



    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }






    fun getTextById(service: AccessibilityService, id: String?): String {
        var text = ""
        val nodeInfo = service?.rootInActiveWindow ?: return text
        val nodeInfoList = nodeInfo.findAccessibilityNodeInfosByViewId(id.toString())
        if (nodeInfoList.isNotEmpty()){
              if (nodeInfoList[0].text != null) {
                  text = nodeInfoList[0].text.toString()
              }
        }

        return text

    }

}