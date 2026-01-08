package com.lygttpod.android.auto.notification
import android.app.Notification
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.activity.BaseLockScreenActivity
import com.google.android.accessibility.ext.activity.TaskByJieSuoHelper
import com.google.android.accessibility.ext.activity.TaskByJieSuoHelper1
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.KeyguardUnLock.unlockMove
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.notification.LatestPendingIntentStore
import com.google.android.accessibility.notification.NotificationInfo
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * 4.3之后系统受到新的通知或者通知被删除时，会触发该service的回调方法
 * 4.4新增extras字段用于获取系统通知具体信息，之前的版本则需要通过反射区获取
 * 注意，需要在 "Settings > Security > Notification access"中，勾选NotificationTask
 */
class NotificationListenerServiceImp : NotificationListenerServiceAbstract() {
    private val tag = "通知栏服务"
    private val serviceJob = SupervisorJob()

    private val serviceScope = CoroutineScope(
        Dispatchers.Default + serviceJob
    )
    override fun targetPackageName() = "com.tencent.mm"
    //覆盖 父类的变量值  是否开启 去重 true 开启
    override val enableShouldHandleFilter: Boolean = true
    /**
     * 系统通知被删掉后触发回调
     */
    override fun asyncHandleNotificationRemoved(
        sbn: StatusBarNotification,
        notification: Notification,
        title: String,
        content: String,
        n_Info: NotificationInfo
    ) {

    }
    /**
     * 系统收到新的通知后触发回调
     */
    override fun asyncHandleNotificationPosted(
        sbn: StatusBarNotification,
        notification: Notification,
        title: String,
        content: String,
        n_Info: NotificationInfo
    ) {

        val packageName = n_Info.pkgName
        val pI = n_Info.pi
        val postTime = n_Info.postTime
        val key = n_Info.key
        Log.e("通知监控1", "title="+ title+" content="+content )
        //================
        serviceScope.launch {
            if (!content.contains("我爱")) return@launch
            if (pI != null) {
                LatestPendingIntentStore.saveLatest(buildNotificationUniqueKey(sbn), pI)
            }
            if (KeyguardUnLock.screenIsOn() && KeyguardUnLock.keyguardIsOn()){
                //已解锁
                AliveUtils.piSend(pI)
            }
            else{
                //未解锁
                //执行解锁逻辑
                val unLockMethod = KeyguardUnLock.getUnLockMethod()
                if (unLockMethod==1 || unLockMethod==2){
                    TaskByJieSuoHelper.startJieSuoTaskInstance(appContext, 1)
                }else if (unLockMethod==3){
                    BaseLockScreenActivity.openBaseLockScreenActivity(cls=LockScreenActivity::class.java, i=1)
                } else if (unLockMethod == 0){
                    //关闭,   啥也不做
                    //是否需要亮屏唤醒屏幕
                    if (!KeyguardUnLock.screenIsOn()) {
                        //屏幕黑屏需要唤醒
                        //sendLog("屏幕黑屏状态,需要唤醒")
                        KeyguardUnLock.wakeScreenOn()
                    } else {
                        //sendLog("屏幕亮屏状态,不需要唤醒")
                    }

                    val pwd = KeyguardUnLock.getScreenPassWord()
                    if (!KeyguardUnLock.deviceIsSecure()||TextUtils.isEmpty(pwd)){
                        //滑动锁屏
                        //因为如果是滑动解锁的话,调用disablekeyguad后,结果将不再准确
                        //所以我们就不再判断键盘是否锁了
                        //锁屏抢开关 15
                        //if (isSupportPluginbackvoicezhen()){
                        KeyguardUnLock.wakeKeyguardOn()
                        //}
                        AliveUtils.piSend(pI)

                    }else{
                        //pin锁屏
                        unlockMove(password = pwd, isJava = true)
                        AliveUtils.piSend(pI)
                    }


                }

                // 方案1还是方案2的 开关
                //if (getUnLockOldOrNew()) {
                //LockScreenActivity.openLockScreenActivity(index = 1)
                //return
                //}
                //TaskByJieSuoHelper.startJieSuoTaskInstance(appContext, 1)

            }
            if (true) return@launch
        }


        //================

    }

    override fun asyncHandleNotificationPostedFor(
        sbn: StatusBarNotification,
        notification: Notification,
        title: String,
        content: String,
        n_Info: NotificationInfo
    ) {
        val packageName = n_Info.pkgName
        val pI = n_Info.pi
        val postTime = n_Info.postTime
        val key = n_Info.key
        Log.e("通知监控2", "title="+ title+" content="+content )
    }

    /**
     * 系统收到新的通知后触发回调
     */
    override fun asyncHandleNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        notification: Notification,
        title: String,
        content: String,
        n_Info: NotificationInfo
    ) {
        val messageList = n_Info.messageStyleList
        for (message in messageList){
            Log.e("通知监控4", "title="+ message.title+" text="+message.text )
        }
        Log.e("通知监控3", "title="+ title+" content="+content)
    }

    override var title:String=""
    override var content:String=""

    /**
     * 当 NotificationListenerService 是可用的并且和通知管理器连接成功时回调
     */
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onListenerConnected() {
        super.onListenerConnected()

    }

    /**
     * 断开连接
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            //重新绑定服务
//            NotificationUtil.toggleNotificationListenerService(this)
        } catch (e: Exception) {

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }




}