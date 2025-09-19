package com.google.android.accessibility.notification
import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.text.TextUtils
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.MMKVUtil

import com.google.android.accessibility.ext.utils.NotificationUtil.getAllSortedByTime
import com.google.android.accessibility.ext.utils.NotificationUtil.getAllSortedMessagingStyleByTime

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
    @Volatile
    private var lastHandleTime: Long = 0L
    @Volatile
    private var lastNotificationKey: String = ""
    private val MIN_HANDLE_INTERVAL = 500L // 500毫秒间隔

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

        fun isTitleAndContentEmpty(title: String, content: String): Boolean {
            val bool = TextUtils.equals(title, appContext.getString(R.string.notificationtitlenull)) &&
                    TextUtils.equals(content, appContext.getString(R.string.notificationcontentnull))

            return bool
        }
        //根据包名来获取应用名称
        fun getAppName(pkgName: String): String {
            val packageManager = appContext.packageManager
            return try {
                /*   if (Build.VERSION.SDK_INT >= 33) {
                       //ApplicationInfoFlags
                       val applicationInfo = packageManager.getApplicationInfo(pkgName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                       packageManager.getApplicationLabel(applicationInfo).toString()

                   }else{
                       val applicationInfo = packageManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
                       packageManager.getApplicationLabel(applicationInfo).toString()

                   }*/
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





    }

    /**
     * 系统通知被删掉后触发回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { listeners.forEach { it.onNotificationRemoved(sbn) } }
        super.onNotificationRemoved(sbn)
        executors.execute {
            val notification = sbn.notification ?: return@execute
            val n_Info = buildNotificationInfo(sbn,notification, null)
            asyncHandleNotificationRemoved(sbn,notification,n_Info.title,n_Info.content,n_Info)
        }


    }

    /**
     * 系统收到新的通知后触发回调  1个参数
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { listeners.forEach { it.onNotificationPosted(sbn) } }
        super.onNotificationPosted(sbn)

        executors.execute {
            val notification = sbn.notification ?: return@execute
            //避免短时间内连续两次调用
            if (!should2Handle(sbn)) return@execute
            var sbns:List<StatusBarNotification> = emptyList()
            val n_info = buildNotificationInfo(sbn,notification, null)
            if (isTitleAndContentEmpty(n_info.title, n_info.content)){
                sbns = getAllSortedByTime(activeNotifications)
                val first_sbn = sbns.getOrNull(0) ?:return@execute
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

        executors2.execute {
            val notification = sbn.notification ?: return@execute
            //避免短时间内连续两次调用
            if (!should2Handle(sbn)) return@execute
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
        executors.execute {
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
        //Kotlin 标准库中的一个函数，类似于 try-catch。
        runCatching { listeners.forEach { it.onDestroy() } }
        super.onDestroy()
        // 关闭执行器以避免内存泄露
        executors.shutdownNow()
        executors2.shutdownNow()
    }

    /**
     * 是否处理该通知：忽略持久系统通知、正在前台的本应用通知等
     */
    fun shouldHandle(sbn: StatusBarNotification): Boolean {
        val postTime = sbn.postTime
        // 检查通知时间是否重复
        if (postTime == lastPostTime) {
            //Log.e("通知去重", "重复的通知时间，已忽略")
            return false
        }
        // 更新上次处理的通知时间
        lastPostTime = postTime
        if (false){
            // 忽略本应用的通知（按需）
            if (sbn.packageName == packageName) return false
            // 忽略 ongoing（常驻）通知（按需）
            val ongoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            if (ongoing) return false
        }
        return true
    }

    /**
     * 是否处理该通知：忽略持久系统通知、正在前台的本应用通知等
     */
    fun should2Handle(sbn: StatusBarNotification): Boolean {
        val currentTime = System.currentTimeMillis()
        val key = sbn.key

        // 检查是否为完全相同的通知
        if (TextUtils.equals(key, lastNotificationKey)) {
            //Log.e("通知去重", "完全相同的通知，已忽略")
            return false
        }

        // 检查时间间隔
        if (currentTime - lastHandleTime < MIN_HANDLE_INTERVAL) {
            //Log.e("通知去重", "处理间隔过短，已忽略")
            return false
        }

        // 更新记录
        lastHandleTime = currentTime
        lastNotificationKey = key
        if (false){
            // 忽略本应用的通知（按需）
            if (sbn.packageName == packageName) return false
            // 忽略 ongoing（常驻）通知（按需）
            val ongoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            if (ongoing) return false
        }
        return true
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
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
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