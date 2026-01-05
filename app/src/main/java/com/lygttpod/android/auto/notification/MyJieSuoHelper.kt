package com.lygttpod.android.auto.notification


import com.google.android.accessibility.ext.activity.BaseLockScreenActivity
import com.google.android.accessibility.ext.activity.TaskByJieSuoHelper
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.MMKVUtil
import com.google.android.accessibility.ext.window.OverlayLog
import com.lygttpod.android.auto.notification.LockScreenActivity.Companion.dealWithPendingIntent


/**
 * 先提前注入 比如在application 的oncreate
 * 实际执行时会调用你子类 MyJieSuoHelper 里改写过的逻辑
 *         TaskByJieSuoHelper.setInstance(MyJieSuoHelper())
 * */
class MyJieSuoHelper : TaskByJieSuoHelper() {

    // 改写解锁逻辑（例如使用不同的 Activity）
    override fun jieSuoBy2(i: Int) {
        //sendLog("🔥 使用自定义解锁方式")
        BaseLockScreenActivity.openBaseLockScreenActivity(cls=LockScreenActivity::class.java, i=i)

    }

    // 改写任务结束逻辑
    override fun doMyWork(i: Int) {
        //sendLog("🔥 执行自定义任务逻辑 i = $i")
        dealWithPendingIntent()
    }

    // 提供真实密码
    override fun getUnlockPassword(): String {

        //轨迹
        MMKVUtil.put(MMKVConst.SHOW_DO_GUIJI, true)

        return KeyguardUnLock.getScreenPassWord() // 真实密码或配置读取
    }

    override fun hasActivity(): Boolean {
        return true
    }
}

