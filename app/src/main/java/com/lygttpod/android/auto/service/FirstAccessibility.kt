package com.lygttpod.android.auto.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

import com.google.android.accessibility.notification.AccessibilityNInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.google.android.accessibility.selecttospeak.XPQEventData
import com.google.android.accessibility.selecttospeak.accessibilityService
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
    }

    override fun asyncHandleAccessibilityEvent(event: AccessibilityEvent) {
//        HBTaskHelper.hbTask(event)
        val s = getTextById(this, "com.tencent.mm:id/obn")
//        Log.e("文本内容", "=: "+s )
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