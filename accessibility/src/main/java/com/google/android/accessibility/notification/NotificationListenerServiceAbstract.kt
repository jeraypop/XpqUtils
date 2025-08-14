package com.google.android.accessibility.notification
import android.media.MediaPlayer
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

import androidx.annotation.RequiresApi

import com.google.android.accessibility.ext.utils.NotificationUtil
import com.google.android.accessibility.ext.utils.NotificationUtil.getNotificationData

import java.util.Collections

import java.util.concurrent.Executors

/**
 * 4.3之后系统受到新的通知或者通知被删除时，会触发该service的回调方法
 * 4.4新增extras字段用于获取系统通知具体信息，之前的版本则需要通过反射区获取
 * 注意，需要在 "Settings > Security > Notification access"中，勾选NotificationTask
 */
abstract class NotificationListenerServiceAbstract : NotificationListenerService() {
    private val TAG = this::class.java.simpleName

    private val executors = Executors.newSingleThreadExecutor()

    abstract fun targetPackageName(): String
    abstract fun asyncHandleNotificationPosted(sbn: StatusBarNotification)
    abstract fun asyncHandleNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap)

    var title:String=""
    var content:String=""
    private  var notiWhen:Long = 0L
    private  var whenPre:Long = 0L

    companion object {
        /**
         * 全局服务实例
         * 用于在应用中获取无障碍服务实例
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
        val mysbn = sbn ?: return
        runCatching { listeners.forEach { it.onNotificationRemoved(mysbn) } }
        super.onNotificationRemoved(sbn)


    }

    /**
     * 系统收到新的通知后出发回调
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val mysbn = sbn ?: return
        executors.run { asyncHandleNotificationPosted(mysbn) }
        runCatching { listeners.forEach { it.onNotificationPosted(mysbn) } }
        super.onNotificationPosted(sbn)
        if (sbn==null)return
        val notification = sbn.notification
        if (notification==null)return
        notiWhen = notification.`when`
        if (notiWhen==whenPre) {
            return
        } else {
            whenPre = notiWhen
        }

        val extraszhedie = notification.extras
        if (extraszhedie==null)return
        val packageName = sbn.packageName
        val pI = notification.contentIntent
        val postTime = sbn.postTime
        val key = sbn.key

        val list = getNotificationData(extraszhedie!!,false)
        title = list.get(0)
        content = list.get(1)



    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {

        val mysbn = sbn ?: return
        val myrankingMap = rankingMap ?: return
        executors.run { asyncHandleNotificationPosted(mysbn, myrankingMap) }
        runCatching { listeners.forEach { it.onNotificationPosted(mysbn, myrankingMap) } }
        super.onNotificationPosted(sbn, rankingMap)
    }


    /**
     * 当 NotificationListenerService 是可用的并且和通知管理器连接成功时回调
     */
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onListenerConnected() {
        instance = this
        runCatching { listeners.forEach { it.onListenerConnected(this) } }
        super.onListenerConnected()

    }

    /**
     * 断开连接
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        runCatching { listeners.forEach { it.onListenerDisconnected() } }


        try {
            //重新绑定服务
            NotificationUtil.toggleNotificationListenerService(applicationContext,NotificationListenerServiceAbstract::class.java)
        } catch (e: Exception) {

        }



    }




    override fun onDestroy() {
        //Kotlin 标准库中的一个函数，类似于 try-catch。
        runCatching { listeners.forEach { it.onDestroy() } }
        super.onDestroy()

    }

}