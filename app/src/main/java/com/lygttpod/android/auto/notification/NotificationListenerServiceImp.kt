package com.lygttpod.android.auto.notification
import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract


/**
 * 4.3之后系统受到新的通知或者通知被删除时，会触发该service的回调方法
 * 4.4新增extras字段用于获取系统通知具体信息，之前的版本则需要通过反射区获取
 * 注意，需要在 "Settings > Security > Notification access"中，勾选NotificationTask
 */
class NotificationListenerServiceImp : NotificationListenerServiceAbstract() {
    private val tag = "通知栏服务"
    override fun targetPackageName() = "com.tencent.mm"
    /**
     * 系统通知被删掉后触发回调
     */
    override fun asyncHandleNotificationRemoved(
        sbn: StatusBarNotification,
        notification: Notification,
        title: String,
        content: String
    ) {

    }
    /**
     * 系统收到新的通知后触发回调
     */
    override fun asyncHandleNotificationPosted(
        sbn: StatusBarNotification,
        notification: Notification,
        title: String,
        content: String
    ) {
        val packageName = sbn.packageName
        val pI = notification.contentIntent
        val postTime = sbn.postTime
        val key = sbn.key
        Log.e("通知监控1", "title="+ title+" content="+content )
    }
    /**
     * 系统收到新的通知后触发回调
     */
    override fun asyncHandleNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        notification: Notification,
        title: String,
        content: String
    ) {
        val packageName = sbn.packageName
        val pI = notification.contentIntent
        val postTime = sbn.postTime
        val key = sbn.key
        Log.e("通知监控2", "title="+ title+" content="+content )
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
    }

}