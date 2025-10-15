package com.google.android.accessibility.ext.utils;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2020/10/16 0016  13:26
 * Description:This is WakeupScreen
 */
public class WakeupScreen {

    public static  PowerManager.WakeLock lock, unLock;
    public static KeyguardManager              km;
    public static KeyguardManager.KeyguardLock kl;



    @SuppressLint("InvalidWakeLockTag")
    public static void wakeUpAndUnlock(Context context){
        //屏锁管理器
        km= (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        kl = km.newKeyguardLock("unLock");
        //解锁
        kl.disableKeyguard();
        //获取电源管理器对象
        PowerManager pm=(PowerManager) context.getSystemService(Context.POWER_SERVICE);
        //获取PowerManager.WakeLock对象,后面的参数|表示同时传入两个值,最后的是LogCat里用的Tag
        unLock = pm.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"bright");
        //点亮屏幕
        unLock.acquire(1*1*66 * 1000);
//        wl.acquire();
        //释放
//        wl.release();
    }

    public static  void lockScreen() {
        if (kl!=null) {
            // 锁屏
            kl.reenableKeyguard();
        }
        if (unLock!=null) {
            // 释放wakeLock，关灯
            if(unLock.isHeld())
                unLock.release();
        }


    }
}
