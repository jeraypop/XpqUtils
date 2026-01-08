package com.lygttpod.android.auto.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

import com.google.android.accessibility.ext.activity.BaseLockScreenActivity
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.notification.LatestPendingIntentStore



import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import kotlin.jvm.java


class LockScreenActivity : BaseLockScreenActivity() {
    companion object {
        @JvmOverloads
        @JvmStatic
        fun openLockScreenActivity(context: Context = appContext, index:Int) {
            val intent = Intent(context, LockScreenActivity::class.java)
            intent.putExtra(MMKVConst.SEND_MSG_INDEX,index)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
        /**
         * 根据索引获取对应的发送列表
         */
        @JvmStatic
        fun dealWithPendingIntent(){
            //已解锁 自动把pi从仓库清除
            val pair = LatestPendingIntentStore.getAndClearLatest()
            if (pair == null) {
                sendLog("未找到最新通知，取消跳转")
                return
            }
            val (key, pending) = pair
            try {
                //val yanchitime = MyApplication.sp()!!.getString(Constants.KEY_JIESUO_YANCHITIME,Constants.KEY_JIESUO_YANCHITIME_DEFAULT)
                //GlobalScope.launch {
                    //delay(yanchitime!!.toLong())
                    //TiperFunction.piSend(pending)
                //}
                AliveUtils.piSend(pending)
                sendLog("通知已跳转(如果实测没跳,则是安卓系统偶尔的抽疯)")
            } catch (e: PendingIntent.CanceledException) {
                sendLog("通知已取消跳转: ${e.message} (key=$key)")
                // 已用 getAndClearLatest() 原子清除，无需额外处理
            } catch (e: Exception) {
                sendLog("通知跳转出错: ${e.message} (key=$key)")
                // 出错情况下也已经从仓库清除了；如需重试，可考虑根据业务再保存或记录
            }


        }

    }

    override suspend fun doMyWork(i: Int) {
        // 如果你想自定义执行逻辑，覆写此方法
        dealWithPendingIntent()
    }

    //覆盖密码方法
    override fun getUnlockPassword(): String? {
        // 从安全存储或运行时来源返回密码
        return KeyguardUnLock.getScreenPassWord()
    }

    override fun hasGesture(): Boolean {
        return true
    }

}