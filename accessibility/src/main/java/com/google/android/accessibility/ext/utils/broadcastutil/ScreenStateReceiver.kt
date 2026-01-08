package com.google.android.accessibility.ext.utils.broadcastutil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.accessibility.ext.utils.KeyguardUnLock

import java.lang.ref.WeakReference

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/1/2  13:10
 * Description:This is ScreenStateReceiver
 */

//1️⃣ 屏幕回调接口
interface ScreenStateCallback {
    fun onScreenOff()
    fun onScreenOn()
    fun onUserPresent()
}
//2️⃣ 独立 Receiver
class ScreenStateReceiver(
    callback: ScreenStateCallback
) : BroadcastReceiver() {

    private val callbackRef = WeakReference(callback)

    override fun onReceive(context: Context, intent: Intent) {
        val cb = callbackRef.get() ?: return
        fun setSuoPingIsOne() {
            if (KeyguardUnLock.keyguardIsGone.get()){
                KeyguardUnLock.suoPingIsOne.set(true)
            }
        }
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.e("监听屏幕啊", "总屏幕已关闭")
                cb.onScreenOff()
                if (KeyguardUnLock.getUnLockMethod()==1 && KeyguardUnLock.getAutoReenKeyguard()){
                    KeyguardUnLock.wakeKeyguardOff(tip = "广播:屏幕已关闭")
                }
                setSuoPingIsOne()
                KeyguardUnLock.keyguardIsGone100.set(false)
            }

            Intent.ACTION_SCREEN_ON -> {
                Log.e("监听屏幕啊", "总屏幕点亮")
                cb.onScreenOn()
                if (KeyguardUnLock.getUnLockMethod()==1 && KeyguardUnLock.getAutoDisableKeyguard()){
                    //禁用键盘锁
                    KeyguardUnLock.wakeKeyguardOn(tip = "广播:屏幕已点亮")
                }
                setSuoPingIsOne()
            }

            Intent.ACTION_USER_PRESENT -> {
                Log.e("监听屏幕啊", "总真正解锁完成")
                cb.onUserPresent()
                //disableKeyguard后,接收不到这个广播
                KeyguardUnLock.keyguardIsGone100.set(true)
                KeyguardUnLock.sendLog("系统广播:屏幕100%解锁成功")
            }
        }
    }
}



