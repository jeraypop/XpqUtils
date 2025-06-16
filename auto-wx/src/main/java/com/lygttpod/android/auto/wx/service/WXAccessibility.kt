package com.lygttpod.android.auto.wx.service

import android.accessibilityservice.AccessibilityService
import android.app.Service
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
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

//    private val windowManager: WindowManager? = null
    private var ignoreView: View? = null
    private var windowManager: WindowManager? = null

    override fun targetPackageName() = "com.tencent.mm"

    override fun onCreate() {
        super.onCreate()
        wxAccessibilityServiceLiveData.value = this
        windowManager = getSystemService<WindowManager>(WindowManager::class.java)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Toast.makeText(this, "22", Toast.LENGTH_SHORT).show()
        service = this
        //前台保活服务
        MyUtilsKotlin.keepAliveByNotification_CLS(service,true,null)
        //0像素悬浮窗保活
        keepAliveByFloatingWindow(true, service,true)

    }

    override fun onDestroy() {
        service = null
        wxAccessibilityServiceLiveData.value = null
        //取消前台保活服务
        MyUtilsKotlin.keepAliveByNotification_CLS(service,false,null)
        //取消0像素悬浮窗保活
        keepAliveByFloatingWindow(false, service,true)
        super.onDestroy()
    }

    override fun asyncHandleAccessibilityEvent(event: AccessibilityEvent) {
//        HBTaskHelper.hbTask(event)
        val s = getTextById(this, "com.tencent.mm:id/obn")
        Log.e("文本内容", "=: "+s )
    }



    fun keepAliveByFloatingWindow(enable: Boolean,service: Service?, isAccessibility: Boolean) {
        if (service==null) return
        if (windowManager==null){
            windowManager = service.getSystemService<WindowManager>(WindowManager::class.java)
        }

        if (enable) {
            val lp = WindowManager.LayoutParams()
            lp.flags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            if (isAccessibility){
                lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }else{
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            lp.gravity = Gravity.START or Gravity.TOP
            lp.format = PixelFormat.TRANSPARENT
            lp.alpha = 0f
            lp.width = 0
            lp.height = 0
            lp.x = 0
            lp.y = 0
            ignoreView = View(service)
            ignoreView?.setBackgroundColor(Color.TRANSPARENT)
            windowManager?.addView(ignoreView, lp)
        } else if (ignoreView != null) {
            windowManager?.removeView(ignoreView)
            ignoreView = null
            windowManager = null
        }
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