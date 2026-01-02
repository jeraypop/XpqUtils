package com.google.android.accessibility.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.KeyguardUnLock.wakeKeyguardOff
import com.google.android.accessibility.ext.utils.KeyguardUnLock.wakeKeyguardOn
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.MMKVUtil

import com.google.android.accessibility.ext.utils.NotificationUtilXpq.getAllSortedByTime
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.getAllSortedMessagingStyleByTime

import com.google.android.accessibility.ext.utils.NotificationUtilXpq.getNotificationData
import com.google.android.accessibility.ext.utils.broadcastutil.BroadcastOwnerType
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateCallback
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateReceiver
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager.CHANNEL_SCREEN
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager.screenFilter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

val notificationServiceLiveData = MutableLiveData<NotificationListenerService?>(null)
val notificationService: NotificationListenerService? get() = notificationServiceLiveData.value

/**
 * 应用级（全局） Executor 提供者 —— 单例
 *
 * 注意：
 * - 线程被设置为守护线程（daemon），避免线程池阻止进程退出。
 * - 因为 executor 与 Service 生命周期解耦，请确保提交到 executor 的任务不要持有短生命周期对象（如 Activity/Service 的非静态引用）。
 *   在提交任务前尽量把需要的数据拷贝出来（例如 buildNotificationInfo 的返回值），以避免在 Service 已销毁时访问已释放资源导致 NPE。
 */
object AppExecutors {

    // 创建守护线程工厂
    private fun daemonThreadFactory(namePrefix: String): ThreadFactory {
        val threadNumber = AtomicInteger(1)
        return ThreadFactory { r ->
            Thread(r, "$namePrefix-${threadNumber.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    }

    // ✅ 单线程守护执行器 1
    val executors: ExecutorService =
        Executors.newSingleThreadExecutor(daemonThreadFactory("notif-exec-1"))

    // ✅ 单线程守护执行器 2
    val executors2: ExecutorService =
        Executors.newSingleThreadExecutor(daemonThreadFactory("notif-exec-2"))

    // ✅ 新增：单线程守护执行器 3
    val executors3: ExecutorService =
        Executors.newSingleThreadExecutor(daemonThreadFactory("notif-exec-3"))
}

abstract class NotificationListenerServiceAbstract : NotificationListenerService(),
    ScreenStateCallback {
    private val TAG = this::class.java.simpleName


    // 由 AppExecutors 提供（全局单例），不要在 Service.onDestroy() 调用 shutdown。
    // 使用时直接调用 AppExecutors.executors.execute { ... } 或 AppExecutors.executors2.execute { ... }

    abstract fun targetPackageName(): String
    @WorkerThread
    abstract fun asyncHandleNotificationRemoved(sbn: StatusBarNotification, notification: Notification,title: String,content: String,n_Info: NotificationInfo)
    @WorkerThread
    abstract fun asyncHandleNotificationPosted(sbn: StatusBarNotification, notification: Notification,title: String,content: String,n_Info: NotificationInfo)
    @WorkerThread
    abstract fun asyncHandleNotificationPostedFor(sbn: StatusBarNotification, notification: Notification,title: String,content: String,n_Info: NotificationInfo)
    @WorkerThread
    abstract fun asyncHandleNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?, notification: Notification,title: String,content: String,n_Info: NotificationInfo)
    @Volatile
    open var title:String=""
    @Volatile
    open var content:String=""
    @Volatile
    private var lastPostTime: Long = 0L

    private val postWhenLock = Any()
    @Volatile
    private var lastPostWhenKey: String? = null
    @Volatile
    private var lastHandleTime: Long = 0L



    private val handleLock = Any()
    @Volatile private var lastHandleTime2: Long = 0L
    @Volatile private var lastHandledUniqueKey: String = ""
    // 是否启用 shouldHandle 过滤器 子类可覆盖
    open val enableShouldHandleFilter: Boolean = true

    companion object {
        // 过期保护：超过这个时间即便 key 相同也会重新处理（单位毫秒）
        private const val EXPIRY_MS: Long = 1_000L // 1 秒，可改为 60_000L (1 分钟) 等

        private const val MIN_HANDLE_INTERVAL = 500L   // 500毫秒间隔
        var instance: NotificationListenerServiceAbstract? = null
            private set
        //val listeners: MutableList<NotificationInterface> = Collections.synchronizedList(arrayListOf<NotificationInterface>())
        val listeners = CopyOnWriteArrayList<NotificationInterface>()

        fun isTitleAndContentEmpty(title: String, content: String): Boolean {
            val bool = TextUtils.equals(title, appContext.getString(R.string.notificationtitlenull)) &&
                    TextUtils.equals(content, appContext.getString(R.string.notificationcontentnull))

            return bool
        }
        //根据包名来获取应用名称
        fun getAppName(pkgName: String): String {
            val packageManager = appContext.packageManager
            return try {
                val applicationInfo = packageManager.getApplicationInfo(pkgName, 0)
                packageManager.getApplicationLabel(applicationInfo).toString()
            } catch (e: Exception) {
                "UnKnown"
            }
        }
        fun isPhoneApp(context: Context = appContext,pkg: String): Boolean {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

            // 获取当前的电话应用
            val currentPackage = telecomManager.defaultDialerPackage
            var isPhoneApp = false
            if (currentPackage!= null){
                isPhoneApp = currentPackage == pkg
            }else{
                isPhoneApp = false
            }
            // 如果默认的拨号应用是我们预期的电话应用（例如系统拨号器），就认为它是电话应用
            return isPhoneApp
        }

        /**
         * 生成通知的唯一标识 key：
         * 使用 sbn.key + pkg + id + tag + postTime + notification.when 组合，
         * 兼容各种厂商 ROM，防止重复、误判。
         */
        @JvmStatic
        fun buildNotificationUniqueKey(sbn: StatusBarNotification): String {
            val n = sbn.notification
            return buildString {
                append(sbn.key)                // 系统级 key
                append("|").append(sbn.packageName)
                append("|").append(sbn.id)
                append("|").append(sbn.tag ?: "")
                append("|").append(sbn.postTime)
                append("|").append(n.`when`)
            }
        }

    }

    /**
     * 系统通知被删掉后触发回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { listeners.forEach { it.onNotificationRemoved(sbn) } }
        super.onNotificationRemoved(sbn)

        // 注意：将需要的数据 (如 NotificationInfo) 在提交前拷贝出来，避免后台线程访问到已销毁的 Service 资源。
        AppExecutors.executors.execute {
            val notification = sbn.notification ?: return@execute
            val n_Info = buildNotificationInfo(sbn,notification, null)
            asyncHandleNotificationRemoved(sbn,notification,n_Info.title,n_Info.content,n_Info)
            // 若你希望当通知被移除时也清理仓库（避免失效的 pendingIntent 被触发）
            val current = LatestPendingIntentStore.peek()
            if (current?.first == n_Info.key) {
                LatestPendingIntentStore.clear()
            }
        }


    }

    /**
     * 系统收到新的通知后触发回调  1个参数
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { listeners.forEach { it.onNotificationPosted(sbn) } }
        super.onNotificationPosted(sbn)


        AppExecutors.executors.execute {
            val notification = sbn.notification ?: return@execute
            //避免短时间内连续两次调用
            if (enableShouldHandleFilter && !shouldHandle(sbn)) return@execute
            var sbns:List<StatusBarNotification> = emptyList()
            val n_info = buildNotificationInfo(sbn,notification, null)
            if (isTitleAndContentEmpty(n_info.title, n_info.content)){
                sbns = getAllSortedByTime(activeNotifications)
              /*  val first_sbn = sbns.getOrNull(0) ?:return@execute
                val first_n = first_sbn.notification ?: return@execute
                val first_n_info = buildNotificationInfo(first_sbn,first_n, null)
                if (isTitleAndContentEmpty(first_n_info.title, first_n_info.content)){
                    val second_sbn = sbns.getOrNull(1)?:return@execute
                    val second_n = second_sbn.notification ?: return@execute
                    val second_n_info = buildNotificationInfo(second_sbn,second_n, null)
                    if (isTitleAndContentEmpty(second_n_info.title, second_n_info.content)){
                        val third_sbn = sbns.getOrNull(2)?: return@execute
                        val third_n = third_sbn.notification ?: return@execute
                        val third_n_info = buildNotificationInfo(third_sbn,third_n, null)
                        if (isTitleAndContentEmpty(third_n_info.title, third_n_info.content)){
                            //什么都不做

                        }else{
                            asyncHandleNotificationPosted(third_sbn,third_n,third_n_info.title,third_n_info.content,third_n_info)
                        }

                    }else{
                        asyncHandleNotificationPosted(second_sbn,second_n,second_n_info.title,second_n_info.content,second_n_info)

                    }

                }else{
                    asyncHandleNotificationPosted(first_sbn,first_n,first_n_info.title,first_n_info.content,first_n_info)
                }*/

                val target = findFirstNonEmptyNotification(sbns, limit = 3)
                target?.let {
                    val tn = it.notification ?: return@execute
                    val tinfo = buildNotificationInfo(it, tn, null)
                    asyncHandleNotificationPosted(it, tn, tinfo.title, tinfo.content, tinfo)
                }

            } else{
                asyncHandleNotificationPosted(sbn,notification,n_info.title,n_info.content,n_info)
            }
            //2 循环遍历 所有活动的通知
            if (sbns.isEmpty()) {
                sbns = getAllSortedByTime(activeNotifications)
            }
            if (true){
                //不带索引
                for (sbn in sbns) {
                    sbn ?: continue
                    val notification = sbn.notification
                    notification ?: continue
                    val n_info = buildNotificationInfo(sbn,notification, null)
                    asyncHandleNotificationPostedFor(sbn,notification,n_info.title,n_info.content,n_info)
                    clearNotification(sbn,n_info.title,n_info.content,n_info.pkgName)
                }
            }else{
                //带索引
                for ((index, sbn) in sbns.withIndex()) {
                    sbn ?: continue
                    val notification = sbn.notification
                    notification ?: continue
                    // 现在可以使用 index 变量获取当前索引
                    //Log.d("LoopIndex", "当前是第 ${index + 1} 个元素")
                    val n_info = buildNotificationInfo(sbn,notification, null)
                    asyncHandleNotificationPostedFor(sbn,notification,n_info.title,n_info.content,n_info)
                    clearNotification(sbn,n_info.title,n_info.content,n_info.pkgName)
                }
            }

        }

    }
    /**
     * 系统收到新的通知后触发回调  2个参数
     */

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        runCatching { listeners.forEach { it.onNotificationPosted(sbn, rankingMap) } }
        super.onNotificationPosted(sbn, rankingMap)

        AppExecutors.executors2.execute {
            val notification = sbn.notification ?: return@execute
            //避免短时间内连续两次调用
            if (enableShouldHandleFilter && !should2Handle(sbn)) return@execute
            val n_Info = buildNotificationInfo(sbn,notification, rankingMap)
            asyncHandleNotificationPosted(sbn,
                rankingMap,
                notification,
                n_Info.title,
                n_Info.content,
                n_Info)



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
        AppExecutors.executors.execute {
            var sbns:List<StatusBarNotification> = emptyList()
            sbns = getAllSortedByTime(activeNotifications)
            for (sbn in sbns) {
                sbn ?: continue
                val notification = sbn.notification
                notification ?: continue
                val n_info = buildNotificationInfo(sbn,notification, null)
                asyncHandleNotificationPostedFor(sbn,notification,n_info.title,n_info.content,n_info)
                clearNotification(sbn,n_info.title,n_info.content,n_info.pkgName)
            }
        }


        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)    // 息屏
            addAction(Intent.ACTION_SCREEN_ON)     // 亮屏（可选）
            addAction(Intent.ACTION_USER_PRESENT)  // 解锁完成
        }

        //registerReceiver(screenReceiver, filter)
        UnifiedBroadcastManager.register(
            CHANNEL_SCREEN,
            this,
            BroadcastOwnerType.NOTIFICATION_SERVICE,
            this,
            ScreenStateReceiver(this),
            screenFilter
        )


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

    fun dealsbnEmpty(sbn: StatusBarNotification?): List<String> {
        sbn ?:return listOf(
            appContext.getString(R.string.notificationtitlenull),
            appContext.getString(R.string.notificationcontentnull)
        )
        val notification = sbn.notification
        notification ?:return listOf(
            appContext.getString(R.string.notificationtitlenull),
            appContext.getString(R.string.notificationcontentnull)
        )
        val extras = notification.extras
        val list = getNotificationData(extras, notification)
        return list
    }



    override fun onDestroy() {
        runCatching { listeners.forEach { it.onDestroy() } }
        super.onDestroy()
        UnifiedBroadcastManager.unregister(
            channel = CHANNEL_SCREEN,
            owner = this,
            context = this
        )
        // **注意**：现在我们将 executors 保持为应用级单例，不在这里 shutdown。
        // 这样可以避免 Service.onDestroy() 与系统随后可能触发的回调（race）导致的 RejectedExecutionException。
        // 仍然建议在提交任务时拷贝所需数据，避免后台执行访问已释放的短生命周期对象。
    }

    /**
     * 轻量去重：使用 postTime + notification.when 组合。
     * - 若 key 与上次相同且未过期 -> 忽略
     * - 若 key 相同但已过期 -> 放行并更新记录
     * - 若 key 不同 -> 放行并更新记录
     * 是否处理该通知：忽略持久系统通知、正在前台的本应用通知等
     * 如果需要也可以在函数内按需忽略本 app 的通知或 ongoing 通知（通过把下面的 false 换成 true 开启）
     */
    fun shouldHandle(sbn: StatusBarNotification): Boolean {
        // 可选过滤：忽略本应用或 ongoing 通知（按需启用）
        if (false) {
            if (sbn.packageName == packageName) return false
            val ongoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            if (ongoing) return false
        }

        val key = buildNotificationUniqueKey(sbn)
        val now = System.currentTimeMillis()

        synchronized(postWhenLock) {
            // 如果 key 相同
            if (TextUtils.equals(key, lastPostWhenKey)) {
                // 检查是否已过期（允许重新处理）
                if (now - lastHandleTime > EXPIRY_MS) {
                    // 过期 -> 允许处理并更新时间（保留相同 key）
                    lastHandleTime = now
                    // lastPostWhenKey 保持不变（也可以重新赋值）
                    // lastPostWhenKey = key
                    return true
                } else {
                    // 未过期 -> 忽略
                    // Log.d(TAG, "重复通知且未过期，忽略: $key")
                    return false
                }
            }

            // key 不同 -> 允许处理并更新记录
            lastPostWhenKey = key
            lastHandleTime = now
            return true
        }
    }

    /**
     * 是否处理该通知：忽略持久系统通知、正在前台的本应用通知等
     */
    fun should2Handle(sbn: StatusBarNotification): Boolean {
        val currentTime = System.currentTimeMillis()
        val uniqueKey =  buildNotificationUniqueKey(sbn)
        synchronized(handleLock) {
            // 检查是否为完全相同的通知
            if (TextUtils.equals(uniqueKey, lastHandledUniqueKey)) {
                Log.e("通知去重", "完全相同的通知，已忽略")
                return false
            }

            // 检查时间间隔
            if (currentTime - lastHandleTime2 < MIN_HANDLE_INTERVAL) {
                Log.e("通知去重", "处理间隔过短，已忽略")
                return false
            }

            // 更新记录
            lastHandleTime2 = currentTime
            lastHandledUniqueKey = uniqueKey
            if (false){
                // 忽略本应用的通知（按需）
                if (sbn.packageName == packageName) return false
                // 忽略 ongoing（常驻）通知（按需）
                val ongoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                if (ongoing) return false
            }
            return true

        }

    }

    // 获取通知的详细信息，包含从 NotificationCompat.MessagingStyle 提取的消息

    fun buildNotificationInfo(sbn: StatusBarNotification,n: Notification, rankingMap: RankingMap?): NotificationInfo {
        val ex = n.extras
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
        val pendingIntent = n.contentIntent


        // 尝试判断 解析 MessagingStyle（如果是聊天类型的通知）
        var messagingStyle: NotificationCompat.MessagingStyle? = null
        try {
            messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (_: Exception) { }
        // 如果是 MessagingStyle 类型的通知，解析该类型的标题和内容
        // 获取对话标题（例如联系人名称或群聊名称）
        val conversationTitle = messagingStyle?.conversationTitle?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.notificationtitlenull)

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
                sender = it.person?.name?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown",  // 发送者，可能为 null，使用默认值 "Unknown"
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

        // 获取其他重要字段
        val category = n.category // e.g. CALL, MESSAGE, EMAIL, ALARM...
        val channelId = if (Build.VERSION.SDK_INT >= 26) n.channelId else null

        // group 信息
        val groupKey = sbn.groupKey
        val isGroup = NotificationCompat.isGroupSummary(n)


        // 从 RankingMap 里取本通知的 ranking
        val ranking = Ranking()
        val hasRanking = rankingMap?.getRanking(sbn.key, ranking) == true
        val importance = if (hasRanking) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ranking.importance
        } else {
            null
        } else null


        val isAmbient = if (hasRanking) ranking.isAmbient else null
        val canShowBadge = if (hasRanking) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ranking.canShowBadge()
        } else {
            null
        } else null
        val overrideGroupKey = if (hasRanking) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ranking.overrideGroupKey
        } else {
            null
        } else null


        return NotificationInfo(
            notification = n,
            pkgName = sbn.packageName,
            appName = getAppName(sbn.packageName),
            id = sbn.id?:1,
            tag = sbn.tag,
            postTime = sbn.postTime,
            title = title,
            content = text,
            bigText = bigText,
            pi = pendingIntent,
            key = sbn.key?:"",
            category = category,
            channelId = channelId,
            groupKey = groupKey,
            isGroupSummary = isGroup?:false,
            importance = importance,
            isAmbient = isAmbient,
            canShowBadge = canShowBadge,
            overrideGroupKey = overrideGroupKey,
            messageStyleList = msgmaplist // 包含来自 MessagingStyle 的消息列表
        )
    }


    fun clearNotification(sbn: StatusBarNotification,title: String,content: String,pkgName: String){
        //某些 包含本应用的  系统通知的消除
        if (isSystemApp(pkg = pkgName)) {
            //电话应用
            if (isPhoneApp(pkg = pkgName))return
            if(title.contains(getAppName(packageName))||
                content.contains(getAppName(packageName))){
                sbn.key?.let {
                    removeNotificationByKey(it)
                    removeWanGuNotificationByKey(sbn,it)
                }
            }
        }

        //只删除本应用的保活通知
        if (!TextUtils.equals(pkgName, packageName))return
        //保活通知的自动消除
        if (AliveUtils.getAC_AliveNotification()){
            val aliveTitle = MMKVUtil.get(
                MMKVConst.FORGROUNDSERVICETITLE,
                appContext.getString(R.string.wendingrun2)
            )
            val aliveContent = MMKVUtil.get(
                MMKVConst.FORGROUNDSERVICECONTENT,
                appContext.getString(R.string.wendingrun4)
            )
            if (title.equals(aliveTitle) ||content.equals(aliveContent)){
                sbn.key?.let {
                    removeWanGuNotificationByKey(sbn,it)
                }
            }
        }
    }

    /**
     * 取消指定通知（按 key）。从 API 21 可用。
     */
    fun removeNotificationByKey(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {

        }
    }
    /**
     * 取消指定顽固通知（按 key）。
     */
    fun removeWanGuNotificationByKey(sbn: StatusBarNotification,key: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (sbn.isOngoing||!sbn.isClearable){
                    snoozeNotification(key, 12 * 60 * 60 * 1000)
                }
            }


        } catch (e: Exception) {

        }
    }

    /**
     * 一键清空通知栏 — 尝试调用系统 API 清空监听到的通知。
     * 注意：不同厂商或系统版本行为可能不同。
     */
    fun clearAllNotifications() {
        try {
            cancelAllNotifications()
        } catch (e: Exception) {

        }
    }

    fun isSystemApp(context: Context =appContext, pkg: String): Boolean {
        try {
            val packageInfo = context.packageManager.getPackageInfo(pkg, 0)
            return packageInfo.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
    }



    fun isPhoneAppWithPermission(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        try {
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val permissions = packageInfo.requestedPermissions
            return permissions?.contains(Manifest.permission.CALL_PHONE) == true
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            return false
        }
    }

    private fun findFirstNonEmptyNotification(candidates: List<StatusBarNotification>, limit: Int = 3): StatusBarNotification? {
        for (sbn in candidates.take(limit)) {
            val n = sbn?.notification ?: continue
            val info = buildNotificationInfo(sbn, n, null)
            if (!isTitleAndContentEmpty(info.title, info.content)) {
                return sbn
            }
        }
        return null
    }


    override fun onScreenOff() {
        // 1️⃣ 屏幕熄灭
        // 一定 = 锁屏即将发生 / 已发生
        Log.e("监听屏幕啊", "notification屏幕已关闭" )
    }

    override fun onScreenOn() {
        // 2️⃣ 屏幕点亮
        // ⚠️ 仍然可能在锁屏界面
        Log.e("监听屏幕啊", "notification屏幕点亮" )
    }

    override fun onUserPresent() {
        // 3️⃣ 真正解锁完成（最重要）
        //disableKeyguard后,接收不到这个广播
        Log.e("监听屏幕啊", "notification真正解锁完成" )
    }


}

data class AccessibilityNInfo(
    val notification: Notification,
    val pkgName: String,
    val appName:String,
    val postTime: Long,
    val title: String,
    val content: String,
    val bigText: String,
    val eventText: String,
    val pi: PendingIntent?,
    val messageStyleList: List<MessageStyleInfo> // 从 MessagingStyle 提取的消息列表
)

data class NotificationInfo(
    val notification: Notification,
    val pkgName: String,
    val appName:String,
    val id: Int,
    val tag: String?,
    val postTime: Long,
    val title: String,
    val content: String,
    val bigText: String,
    val pi: PendingIntent?,
    val key: String,
    val category: String?,
    val channelId: String?,
    val groupKey: String?,
    val isGroupSummary: Boolean,
    val importance: Int?,
    val isAmbient: Boolean?,
    val canShowBadge: Boolean?,
    val overrideGroupKey: String?,
    val messageStyleList: List<MessageStyleInfo> // 从 MessagingStyle 提取的消息列表
)

data class MessageStyleInfo(
    val timestamp: Long,
    val title: String,
    val sender: String,
    val text: String
)
