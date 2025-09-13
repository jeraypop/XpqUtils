package com.lygttpod.android.auto.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.MutableLiveData
import com.google.android.accessibility.ext.toast
import com.google.android.accessibility.ext.utils.AliveUtils.keepAliveByFloatingWindow
import com.google.android.accessibility.ext.utils.AliveUtils.keepAliveByNotification_CLS
import com.google.android.accessibility.notification.AccessibilityNInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.google.android.accessibility.selecttospeak.accessibilityService

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

    override fun targetPackageName() = "com.tencent.mm"



    override fun onServiceConnected() {
        super.onServiceConnected()
        // 根据需要动态设置监听的包名
        val targetPackages = getTargetPackageList() // 从配置或网络获取包名列表

        val serviceInfo = serviceInfo
        // 动态设置包名
//        serviceInfo.packageNames = arrayOf("com.tencent.mm", "com.tencent.wework")

        serviceInfo.packageNames = targetPackages.toTypedArray()

        // 可以同时修改其他属性
        serviceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
         //只能在 onServiceConnected() 方法中调用 setServiceInfo()

        //如果设置了,那么通过无障碍监听通知消息就不行了
        //建议还是在onAccessibilityEvent()过滤包名吧
//        setServiceInfo(serviceInfo)



    }
     private fun getTargetPackageList(): List<String> {
         // 从SharedPreferences、网络配置等获取动态包名列表
         return listOf("com.tencent.mm", "com.tencent.wework")
     }
    override fun asyncHandleAccessibilityEvent(event: AccessibilityEvent) {
//        HBTaskHelper.hbTask(event)
        val s = getTextById(this, "com.tencent.mm:id/obn")
//        Log.e("文本内容", "=: "+s )
    }


     override fun onInterrupt() {
        super.onInterrupt()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }



    override fun onDestroy() {
        super.onDestroy()
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