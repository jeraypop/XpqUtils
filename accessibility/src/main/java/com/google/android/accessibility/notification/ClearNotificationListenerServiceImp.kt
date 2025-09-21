package com.google.android.accessibility.notification
import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi


/**
 * 4.3之后系统受到新的通知或者通知被删除时，会触发该service的回调方法
 * 4.4新增extras字段用于获取系统通知具体信息，之前的版本则需要通过反射区获取
 * 注意，需要在 "Settings > Security > Notification access"中，勾选NotificationTask
 */
class ClearNotificationListenerServiceImp : NotificationListenerServiceAbstract() {
    private val tag = "通知栏服务"
    override fun targetPackageName() = "com.tencent.mm"
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
    }

    override fun onDestroy() {
        super.onDestroy()
    }




}