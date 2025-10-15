package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.AssistsServiceListener
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.getAllSortedMessagingStyleByTime
import com.google.android.accessibility.ext.window.AssistsWindowManager
import com.google.android.accessibility.notification.AccessibilityNInfo
import com.google.android.accessibility.notification.AppExecutors
import com.google.android.accessibility.notification.MessageStyleInfo
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract.Companion.getAppName
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract.Companion.isTitleAndContentEmpty
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors


val accessibilityServiceLiveData = MutableLiveData<AccessibilityService?>(null)
val accessibilityService: AccessibilityService? get() = accessibilityServiceLiveData.value
abstract class SelectToSpeakServiceAbstract : AccessibilityService() {
    private val TAG = this::class.java.simpleName
    //用于TYPE_WINDOW_CONTENT_CHANGED的简单防抖（按包名）
    //ConcurrentHashMap 创建一个 线程安全的 HashMap  在多线程环境下同时读写，不需要自己加锁
    private val lastWindowContentHandledAt = ConcurrentHashMap<String, Long>()
    private var WINDOW_CONTENT_DEBOUNCE_MS = 300L // 每个包300ms内只处理一次（根据需要调节）

    @Volatile
    private var lastEventTime: Long = 0L
    // 如果你只对特定包感兴趣，可以在这里维护白名单/黑名单
    private val packageNamesFilter: Set<String>? = null // e.g. setOf("com.whatsapp", "com.tencent.mm")

    // 已转移所有权的副本映射（identityHash -> node 副本）
    private val ownershipMap = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    abstract fun targetPackageName(): String

    abstract fun asyncHandleAccessibilityEvent(event: AccessibilityEvent)
    open fun asyncHandleAccessibilityNotification(notification: Notification, title: String, content: String, a_n_Info: AccessibilityNInfo){}
    /**
     * 默认：传入的是副本集合（父类在调用后会回收这些副本）。
     * 如果子类想接管某个副本以跨线程或长期持有，请使用 submitNodeForChild(originalNode) 机制。
     */
    open fun asyncHandle_WINDOW_STATE_CHANGED(root: AccessibilityNodeInfo,nodeInfoSet: MutableSet<AccessibilityNodeInfo>,pkgName: String, className: String){}
    open fun asyncHandle_WINDOW_CONTENT_CHANGED(root: AccessibilityNodeInfo,nodeInfoSet: Set<AccessibilityNodeInfo>,pkgName: String){}

    open fun asyncHandle_VIEW_SCROLLED(event: AccessibilityEvent){}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        accessibilityServiceLiveData.value = this
        AssistsWindowManager.init(this)
        runCatching { listeners.forEach { it.onServiceConnected(this) } }

        val info = AccessibilityServiceInfo().apply {
            // 订阅的事件类型（组合多个）
            eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED

            // 反馈类型（不影响我们获取事件，通常设置为 FEEDBACK_GENERIC）
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // 要求返回更多的事件细节（extras / urls 等），根据需要打开
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            // 是否要接收通知栏事件（有些设备/ROM 需要）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }

            // 可选：限制只监听特定应用（null 表示监听所有）
            packageNames = packageNamesFilter?.toTypedArray()
        }
           //默认关闭
//        serviceInfo = info

        if (AliveUtils.getKeepAliveByNotification()){
            //前台保活服务   如果放在子类中 可传入 class了
            AliveUtils.keepAliveByNotification_CLS(this,true,null)
        }
        AliveUtils.keepAliveByFloatingWindow(this,AliveUtils.getKeepAliveByFloatingWindow())

    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        instance = this
        // 把事件丢给单线程 executor 处理（保证有序）
        AppExecutors.executors3.execute {
            asyncHandleAccessibilityEvent(event)
            dealEvent(event)
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
        AliveUtils.keepAliveByFloatingWindow(this,false)
        // 清理 ownershipMap 中可能未释放的副本，避免泄露
        cleanupOwnershipMap()
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
        //val listeners: MutableList<AssistsServiceListener> = Collections.synchronizedList(arrayListOf<AssistsServiceListener>())

        // 改成 CopyOnWriteArrayList，更安全，适合回调监听器场景
        val listeners = java.util.concurrent.CopyOnWriteArrayList<AssistsServiceListener>()
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
    // 在 AccessibilityService 中使用  ，构建通知解析结果
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

    private fun dealEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            //通知改变
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val eventTime = event.eventTime
                if (!shouldHandle(eventTime)) return
                val pkgName = event.packageName?.toString() ?: "UnKnown"
                // 方式一：直接从 event.text 获取（简单但可能不完整）
                val eventText = event.text?.joinToString(separator = " ")?.takeIf { it.isNotBlank() } ?: "（event.text 为空或不完整）"
                // 方式二：从 parcelableData 获取 Notification（更完整）
                val notification = event.parcelableData as? Notification ?: return
                val a_n_Info =
                    buildAccessibilityNInfo(notification, pkgName, eventTime,eventText)
                asyncHandleAccessibilityNotification(notification,a_n_Info.title,a_n_Info.content,a_n_Info)

            }
            //状态改变
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val originalRoot = try { rootInActiveWindow } catch (_: Throwable) { null }
                originalRoot ?: return
                // 窗口变化（Activity 切换、弹窗显示等），你可以在这里处理或记录
                val pkg = originalRoot.packageName?.toString()?:return
                val ev_pkg = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""
                if (!TextUtils.equals(pkg, ev_pkg)) return
                val nodeInfoSet: MutableSet<AccessibilityNodeInfo> = mutableSetOf()
                // 创建节点副本 复制 active root
                val rootCopy = copyNodeCompat(originalRoot)
                rootCopy?.let { nodeInfoSet.add(it) }

                // 复制 windows roots（兼容性安全调用）
                val windows = try { getWindows() } catch (_: Throwable) { null }
                windows?.forEach { win ->
                    val winRoot = try { win?.root } catch (_: Throwable) { null }
                    val winCopy = copyNodeCompat(winRoot)
                    winCopy?.let { nodeInfoSet.add(it) }
                }
                if (nodeInfoSet.isEmpty()) return
                // 调用子类处理 —— 默认父类会在此之后回收这些副本
                try {
                    rootCopy?.let { asyncHandle_WINDOW_STATE_CHANGED(it, nodeInfoSet, pkg, className) }
                } catch (t: Throwable) {

                } finally {
                    // 回收副本（仅副本，父类负责；若子类要接管，请使用 submitNodeForChild）
                    nodeInfoSet.forEach { recycleCompat(it) }
                }

            }

            //内容改变
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                // 频率限制，防止大量重复事件
                //if (!shouldHandleWindowContentChanged(pkg)) return

                // 额外过滤：如果你有白名单，先检查
                //if (packageNamesFilter != null && pkg !in packageNamesFilter) return@execute

                // event.source 可能为 null（被回收或权限不够）
                val sourceOriginal = try { event.source } catch (_: Throwable) { null }
                sourceOriginal ?: return

                val sourceCopy = copyNodeCompat(sourceOriginal)
                if (sourceCopy == null) {
                    // 复制失败，不要传原始 node 出去
                    return
                }
                try {
                    asyncHandle_WINDOW_CONTENT_CHANGED(sourceCopy, setOf(sourceCopy), pkg)
                } catch (t: Throwable) {

                } finally {
                    // 默认回收副本；若子类接管，请使用 submitNodeForChild
                    recycleCompat(sourceCopy)
                }

            }
            //滑动改变
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                asyncHandle_VIEW_SCROLLED(event)
            }

            else -> {
                // 其他事件忽略
            }
        }
    }

    private fun copyNodeCompat(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                AccessibilityNodeInfo(node)
            } else {
                AccessibilityNodeInfo.obtain(node)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "copyNodeCompat failed", t)
            null
        }
    }

    private fun recycleCompat(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < 34) {
            try { node.recycle() } catch (_: Throwable) { /* ignore */ }
        } else {
            // API34+ recycle 已废弃且为空实现，不必调用
        }
    }
    private fun shouldHandleWindowContentChanged(pkg: String?): Boolean {
        val key = pkg ?: "unknown_pkg"
        val now = System.currentTimeMillis()
        val last = lastWindowContentHandledAt[key] ?: 0L
        return if (now - last >= WINDOW_CONTENT_DEBOUNCE_MS) {
            lastWindowContentHandledAt[key] = now
            true
        } else {
            false
        }
    }

    // ---------- 子类接管/释放机制（用于异步/长期持有副本） ----------
    /**
     * 把 system node 的副本交给子类处理（在当前 executor 中同步调用 childHandle）。
     * childHandle 返回 true 表示子类接管副本，必须在完成时调用 releaseNode(copy)。
     * 返回 false 表示不接管，父类会回收副本。
     *
     * 注意：此方法期望在 executor 的线程上下文中被调用（dealEvent 已在 executor 中）。
     */
    fun submitNodeForChild(node: AccessibilityNodeInfo?, childHandle: (AccessibilityNodeInfo) -> Boolean) {
        if (node == null) return
        val copy = copyNodeCompat(node) ?: return
        var takenByChild = false
        try {
            takenByChild = try {
                childHandle(copy)
            } catch (t: Throwable) {
                Log.w(TAG, "childHandle error", t)
                false
            }
        } finally {
            if (!takenByChild) {
                // 父类负责回收
                recycleCompat(copy)
            } else {
                // 子类接管：记录，等待子类 later 调用 releaseNode(copy)
                ownershipMap[System.identityHashCode(copy)] = copy
            }
        }
    }

    /**
     * 子类在异步处理完成后必须调用此方法释放先前接管的 node（由 submitNodeForChild 标记）。
     */
    fun releaseNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val key = System.identityHashCode(node)
        val removed = ownershipMap.remove(key)
        if (removed != null) {
            recycleCompat(removed)
        } else {
            Log.w(TAG, "releaseNode: node not found in ownershipMap")
        }
    }

    private fun cleanupOwnershipMap() {
        for ((_, node) in ownershipMap) {
            try { recycleCompat(node) } catch (_: Throwable) { }
        }
        ownershipMap.clear()
    }

    /**
     * 直接把一个已经是副本的 node（父类传入的 copy，或通过 copyNodeCompat 得到的副本）
     * 标记为“已被子类接管”，以便父类不再回收它。
     * 子类必须在完成后调用 releaseNode(node) 释放（回收）。
     *
     * 返回 true 表示登记成功（以后父类不会自动回收该 node）。
     *
     * 注意：务必保证传入 node 是安全的副本（父类在调用时传给你的那些副本，
     * 或你自己用 copyNodeCompat 创建的）。
     * 千万不要把 getRootInActiveWindow()/event.source 的原始系统 node 直接传进来做 claim。
     *
     */
    fun claimNodeDirectly(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        // 仅登记，不做其它复制/回收
        val key = System.identityHashCode(node)
        // 如果已有条目，返回 false（表示重复接管）
        if (ownershipMap.putIfAbsent(key, node) == null) {
            return true
        }
        return false
    }


    fun extractNodeSummary(node: AccessibilityNodeInfo): NodeSummary {
        // 从节点中提取包名和类名（如果存在）
        val packageName = node.packageName?.toString()
        val className = node.className?.toString()

        // 从节点中提取文本（如果存在）
        val nodeText = node.text?.toString()

        // 获取资源ID（视图ID）
        val viewId = node.viewIdResourceName

        // 获取节点的坐标和尺寸
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // 获取节点的描述（如果有的话）
        val describe = node.contentDescription?.toString()

        // 返回 NodeSummary
        return NodeSummary(
            packageName = packageName,
            className = className,
            nodeText = nodeText,
            viewId = viewId,
            bounds = bounds,
            describe = describe
        )
    }



}

data class NodeSummary(
    val packageName: String?,
    val className: String?,
    val nodeText: String?,
    val viewId: String?,
    val bounds: Rect,
    val describe: String?
)