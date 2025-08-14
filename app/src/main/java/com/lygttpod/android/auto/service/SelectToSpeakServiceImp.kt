package com.lygttpod.android.auto.service

import android.accessibilityservice.AccessibilityService
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.google.android.accessibility.ext.toast
import com.google.android.accessibility.ext.utils.AliveUtils.keepAliveByFloatingWindow
import com.google.android.accessibility.ext.utils.AliveUtils.keepAliveByNotification_CLS
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract

//import com.lygttpod.android.auto.wx.helper.ToastUtil.keepAliveByNotification_CLS
import java.util.concurrent.atomic.AtomicBoolean

val wxAccessibilityServiceLiveData = MutableLiveData<AccessibilityService?>(null)
val wxAccessibilityService: AccessibilityService? get() = wxAccessibilityServiceLiveData.value

open class WXAccessibility : SelectToSpeakServiceAbstract() {

    companion object {
        var isInWXApp = AtomicBoolean(false)
        var service: WXAccessibility? = null
        fun getWXService(): AccessibilityService? {
            return service
        }
    }

    override fun targetPackageName() = "com.tencent.mm"

    override fun onCreate() {
        super.onCreate()
        wxAccessibilityServiceLiveData.value = this

    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        toast("22")
        service = this
        //前台保活服务
        keepAliveByNotification_CLS(service,true,null)
        //0像素悬浮窗保活
        keepAliveByFloatingWindow(service, true, true)

    }

    override fun asyncHandleAccessibilityEvent(event: AccessibilityEvent) {
//        HBTaskHelper.hbTask(event)
        val s = getTextById(this, "com.tencent.mm:id/obn")
        Log.e("文本内容", "=: "+s )
    }

    override fun onInterrupt() {
        super.onInterrupt()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }



    override fun onDestroy() {
        service = null
        wxAccessibilityServiceLiveData.value = null
        //取消前台保活服务
        keepAliveByNotification_CLS(service,false,null)
        //取消0像素悬浮窗保活
        keepAliveByFloatingWindow(service,false,true)
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