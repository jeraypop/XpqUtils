package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.AssistsServiceListener
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.NotificationUtil.getAllSortedMessagingStyleByTime
import com.google.android.accessibility.ext.window.AssistsWindowManager
import com.google.android.accessibility.notification.AccessibilityNInfo
import com.google.android.accessibility.notification.MessageStyleInfo
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract.Companion.getAppName
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract.Companion.isTitleAndContentEmpty
import java.util.Collections
import java.util.concurrent.Executors

val accessibilityServiceLiveData = MutableLiveData<AccessibilityService?>(null)
val accessibilityService: AccessibilityService? get() = accessibilityServiceLiveData.value
abstract class SelectToSpeakServiceAbstract : AccessibilityService() {
    private val TAG = this::class.java.simpleName
    @Volatile
    private var lastEventTime: Long = 0L
    private val executors = Executors.newSingleThreadExecutor()

    abstract fun targetPackageName(): String

    abstract fun asyncHandleAccessibilityEvent(event: AccessibilityEvent)
    open fun asyncHandleAccessibilityNotification(notification: Notification, title: String, content: String, a_n_Info: AccessibilityNInfo){}

    override fun onServiceConnected() {
        instance = this
        accessibilityServiceLiveData.value = this
        AssistsWindowManager.init(this)
//        Log.d(TAG, "onServiceConnected: ")
        runCatching { listeners.forEach { it.onServiceConnected(this) } }

        if (AliveUtils.getKeepAliveByNotification()){
            //前台保活服务   如果放在子类中 可传入 class了
            AliveUtils.keepAliveByNotification_CLS(this,true,null)
        }
        AliveUtils.keepAliveByFloatingWindow(this,AliveUtils.getKeepAliveByFloatingWindow(),true)

    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        instance = this
        executors.run {
            asyncHandleAccessibilityEvent(event)

            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                val eventTime = event.eventTime
                shouldHandle(eventTime)
                val pkgName = event.packageName?.toString() ?: "UnKnown"
                // 方式一：直接从 event.text 获取（简单但可能不完整）
                val eventText = event.text?.joinToString(separator = " ")?.takeIf { it.isNotBlank() } ?: "（event.text 为空或不完整）"
                // 方式二：从 parcelableData 获取 Notification（更完整）
                val notification = event.parcelableData as? Notification ?: return
                val a_n_Info =
                    buildAccessibilityNInfo(notification, pkgName, eventTime,eventText)
                asyncHandleAccessibilityNotification(notification,a_n_Info.title,a_n_Info.content,a_n_Info)

            }
        }
        runCatching { listeners.forEach { it.onAccessibilityEvent(event) } }
    }
    /**
     * 服务解绑时调用
     * 清除服务实例并通知所有监听器
     * @param intent 解绑的Intent
     * @return 是否调用父类的onUnbind方法
     */
    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { listeners.forEach { it.onUnbind() } }
        return super.onUnbind(intent)
    }
    override fun onInterrupt() {
        runCatching { listeners.forEach { it.onInterrupt() } }
    }



    override fun onDestroy() {
        instance = null
        accessibilityServiceLiveData.value = null
        if (AliveUtils.getKeepAliveByNotification()){
            //前台保活服务   如果放在子类中 可传入 class了
            AliveUtils.keepAliveByNotification_CLS(this,false,null)
        }
        AliveUtils.keepAliveByFloatingWindow(this,false,true)
        super.onDestroy()
    }

    companion object {
        /**
         * 全局服务实例
         * 用于在应用中获取无障碍服务实例
         * 当服务未启动或被销毁时为null
         *
         * 这段代码声明了一个名为 instance 的可变变量，类型为 SelectToSpeakServiceAbstract?（可空类型），
         * 并将其 setter 设为私有，表示外部无法直接修改该变量值。
         * 作用：实现一个私有可变、外部只读的单例引用。
         *
         */
        var instance: SelectToSpeakServiceAbstract? = null
            private set
        /**
         * 服务监听器列表
         * 使用线程安全的集合存储所有监听器
         * 用于分发服务生命周期和无障碍事件
         */
        val listeners: MutableList<AssistsServiceListener> = Collections.synchronizedList(arrayListOf<AssistsServiceListener>())
    }

    fun shouldHandle(eventTime: Long): Boolean {
        // 检查通知时间是否重复
        if (eventTime == lastEventTime) {
            //Log.e("通知去重", "重复的通知时间，已忽略")
            return false
        }
        // 更新上次处理的通知时间
        lastEventTime = eventTime
        return true
    }
    // 在 AccessibilityService 中使用
    fun buildAccessibilityNInfo(notification: Notification, pkgName: String, eventTime: Long,eventText: String): AccessibilityNInfo {
        val ex = notification.extras
        fun getStringOrFallback(key: String, fallback: String): String {
            return ex?.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
                ?: ex?.getString(key, fallback)
                ?: fallback
        }
        // 获取标题
        var title = getStringOrFallback(Notification.EXTRA_TITLE, appContext.getString(R.string.notificationtitlenull))
        // 获取大文本
        val bigText = getStringOrFallback(Notification.EXTRA_BIG_TEXT, appContext.getString(R.string.notificationcontentnull))
        // 获取文本，如果 EXTRA_TEXT 为空，则尝试获取 EXTRA_BIG_TEXT
        var text = getStringOrFallback(Notification.EXTRA_TEXT, bigText)
        val pendingIntent = notification.contentIntent


        // 尝试判断 解析 MessagingStyle（如果是聊天类型的通知）
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        // 如果是 MessagingStyle 类型的通知，解析该类型的标题和内容
        // 获取对话标题（例如联系人名称或群聊名称）
        val conversationTitle: String = (messagingStyle?.conversationTitle ?: appContext.getString(R.string.notificationtitlenull)).toString()
        // 获取消息列表
        val messageList = messagingStyle?.messages?:emptyList()
        // 获取所有按时间排序messagingStyle的消息列表 （降序）
        val sortedmessageList = getAllSortedMessagingStyleByTime(messageList)
        //获取最新的一条消息
        // sortedmessageList?.firstOrNull()
        // sortedmessageList.getOrNull(0) 两个是等价的
        //val first_msg = sortedmessageList?.firstOrNull()

        //类型：List<MessageStyleInfo> 转换为msgmaplist（保持降序排序）
        val msgmaplist = sortedmessageList?.map {
            MessageStyleInfo(
                timestamp = it.timestamp,  // 时间戳，假设它不会为 null，但可能为 0
                title = conversationTitle,
                sender = it.person?.toString() ?: "Unknown",  // 发送者，可能为 null，使用默认值 "Unknown"
                text = it.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: appContext.getString(R.string.notificationcontentnull) // 消息内容，可能为 null 或空白，使用默认值
            )
        } ?: emptyList()

        if (isTitleAndContentEmpty(title, text)){
            //获取最新一条消息
            msgmaplist.firstOrNull()?.let {
                title = it.title
                text =  if (TextUtils.equals(appContext.getString(R.string.notificationcontentnull), it.text)){
                    it.text
                }else{
                    it.sender + ":" + it.text
                }
            }
        }

        return AccessibilityNInfo(
            notification = notification,
            pkgName = pkgName,
            appName = getAppName(pkgName),
            postTime = eventTime,
            title = title,
            content = text,
            bigText = bigText,
            eventText = eventText,
            pi = pendingIntent,
            messageStyleList = msgmaplist // 包含来自 MessagingStyle 的消息列表
        )
    }


}