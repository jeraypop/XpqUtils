package com.google.android.accessibility.notification
import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.MutableLiveData
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.NotificationUtil
import com.google.android.accessibility.ext.utils.NotificationUtil.getAllSortedByTime
import com.google.android.accessibility.ext.utils.NotificationUtil.getLatestNotification
import com.google.android.accessibility.ext.utils.NotificationUtil.getNotificationData
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 4.3之后系统受到新的通知或者通知被删除时，会触发该service的回调方法
 * 4.4新增extras字段用于获取系统通知具体信息，之前的版本则需要通过反射区获取
 * 注意，需要在 "Settings > Security > Notification access"中，勾选NotificationTask
 */
val notificationServiceLiveData = MutableLiveData<NotificationListenerService?>(null)
val notificationService: NotificationListenerService? get() = notificationServiceLiveData.value
abstract class NotificationListenerServiceAbstract : NotificationListenerService() {
    private val TAG = this::class.java.simpleName

    private val executors = Executors.newSingleThreadExecutor()
    private val executors2 = Executors.newSingleThreadExecutor()

    abstract fun targetPackageName(): String
    abstract fun asyncHandleNotificationRemoved(sbn: StatusBarNotification, notification: Notification,title: String,content: String)
    abstract fun asyncHandleNotificationPosted(sbn: StatusBarNotification, notification: Notification,title: String,content: String)
    abstract fun asyncHandleNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap, notification: Notification,title: String,content: String)
    @Volatile
    open var title:String=""
    @Volatile
    open var content:String=""
    @Volatile
    private  var notiWhen:Long = 0L
    @Volatile
    private  var whenPre:Long = 0L
    @Volatile
    private  var notiWhen2:Long = 0L
    @Volatile
    private  var whenPre2:Long = 0L
    @Volatile
    private  var notiWhen3:Long = 0L
    @Volatile
    private  var whenPre3:Long = 0L

    companion object {
        /**
         * 全局服务实例
         * 用于在应用中获取通知栏服务实例
         * 当服务未启动或被销毁时为null
         *
         * 这段代码声明了一个名为 instance 的可变变量，类型为 NotificationListenerServiceAbstract?（可空类型），
         * 并将其 setter 设为私有，表示外部无法直接修改该变量值。
         * 作用：实现一个私有可变、外部只读的单例引用。
         *
         */
        var instance: NotificationListenerServiceAbstract? = null
            private set
        /**
         * 服务监听器列表
         * 使用线程安全的集合存储所有监听器
         * 用于分发服务生命周期和无障碍事件
         */
        val listeners: MutableList<NotificationInterface> = Collections.synchronizedList(arrayListOf<NotificationInterface>())
    }

    /**
     * 系统通知被删掉后出发回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { listeners.forEach { it.onNotificationRemoved(sbn) } }
        super.onNotificationRemoved(sbn)
        executors.run {
            val notification = sbn.notification
            notification ?: return
            val extras = notification.extras
            val list = getNotificationData(extras)
            asyncHandleNotificationRemoved(sbn,notification,list.get(0),list.get(1))
        }


    }

    /**
     * 系统收到新的通知后出发回调  1个参数
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { listeners.forEach { it.onNotificationPosted(sbn) } }
        super.onNotificationPosted(sbn)

        executors.run {
            val notification = sbn.notification
            notification ?: return
//            notiWhen = notification.`when`
            notiWhen = sbn.postTime
            if (notiWhen==whenPre) {
                return
            } else {
                whenPre = notiWhen
            }
//            Log.e("通知去重", "notiWhen="+ notiWhen+" postTime="+sbn.postTime )
            val extras = notification.extras
            val list = getNotificationData(extras)
            title = list.get(0)
            content = list.get(1)
            if (TextUtils.equals(title, appContext.getString(R.string.notificationtitlenull)) &&
                TextUtils.equals(content, appContext.getString(R.string.notificationcontentnull))

                ){
                //这个方法是得到一个sbn的数组，就是所有的应用软件的通知
                //从数组中获取最新的一条通知
                val sbn = getLatestNotification(activeNotifications)
                sbn ?: return
                val notification = sbn.notification
                notification ?: return
                val extras = notification.extras
                val list = getNotificationData(extras)
                title = list.get(0)
                content = list.get(1)
                asyncHandleNotificationPosted(sbn,notification,title,content)
            }else{
                asyncHandleNotificationPosted(sbn,notification,title,content)
            }



        }

    }
    /**
     * 系统收到新的通知后出发回调  2个参数
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        val myrankingMap = rankingMap ?: return
        runCatching { listeners.forEach { it.onNotificationPosted(sbn, myrankingMap) } }
        super.onNotificationPosted(sbn, rankingMap)

        executors2.run {
            val notification = sbn.notification
            notification ?: return
//            notiWhen2 = notification.`when`
            notiWhen2 = sbn.postTime
            if (notiWhen2==whenPre2) {
                return
            } else {
                whenPre2 = notiWhen2
            }

            //获取所有通知，并按时间从新到旧排序
            val sbns = getAllSortedByTime(activeNotifications)
            for (sbn in sbns) {
                sbn ?: continue
                val notification = sbn!!.notification
                notification ?: continue
//                notiWhen3 = notification.`when`
                notiWhen3 = sbn.postTime
                if (notiWhen3==whenPre3) {
                    continue
                } else {
                    whenPre3 = notiWhen3
                }
                val extras = notification.extras
                val list = getNotificationData(extras)
                asyncHandleNotificationPosted(sbn, myrankingMap,notification,list.get(0),list.get(1))
            }


        }


    }


    /**
     * 当 NotificationListenerService 是可用的并且和通知管理器连接成功时回调
     */
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onListenerConnected() {
        instance = this
        notificationServiceLiveData.value = this
        runCatching { listeners.forEach { it.onListenerConnected(this) } }
        super.onListenerConnected()

    }

    /**
     * 断开连接
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        notificationServiceLiveData.value = null
        runCatching { listeners.forEach { it.onListenerDisconnected() } }





    }




    override fun onDestroy() {
        //Kotlin 标准库中的一个函数，类似于 try-catch。
        runCatching { listeners.forEach { it.onDestroy() } }
        super.onDestroy()

    }

}