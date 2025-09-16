package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    private val executors = Executors.newSingleThreadExecutor()
    // 如果你只对特定包感兴趣，可以在这里维护白名单/黑名单
    private val packageNamesFilter: Set<String>? = null // e.g. setOf("com.whatsapp", "com.tencent.mm")


    abstract fun targetPackageName(): String

    abstract fun asyncHandleAccessibilityEvent(event: AccessibilityEvent)
    open fun asyncHandleAccessibilityNotification(notification: Notification, title: String, content: String, a_n_Info: AccessibilityNInfo){}

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
        AliveUtils.keepAliveByFloatingWindow(this,AliveUtils.getKeepAliveByFloatingWindow(),true)

    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        instance = this
        executors.execute {
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
        AliveUtils.keepAliveByFloatingWindow(this,false,true)
        super.onDestroy()
        // 关闭执行器以避免内存泄露
        executors.shutdownNow()
        //executors2.shutdownNow()
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

    private fun dealEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            //通知改变
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
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
            //状态改变
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val root = getRootInActiveWindow()
                root ?: return
                // 窗口变化（Activity 切换、弹窗显示等），你可以在这里处理或记录
                val pkg = root.packageName?.toString()?:return
                val ev_pkg = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""

                if (!TextUtils.equals(pkg, ev_pkg)){
                    return
                }



                val nodeInfoSet: MutableSet<AccessibilityNodeInfo> = mutableSetOf()
                nodeInfoSet.add(root)
                val windowInfoSet: MutableSet<AccessibilityWindowInfo> = mutableSetOf()
                windowInfoSet.addAll(getWindows())
                for (windowInfo in windowInfoSet){
                    windowInfo?.root?:continue
                    nodeInfoSet.add(windowInfo.root)

                }
                asyncHandle_WINDOW_STATE_CHANGED(root, nodeInfoSet,pkg,className)

            }

            //内容改变
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                // 频率限制，防止大量重复事件
                //if (!shouldHandleWindowContentChanged(pkg)) return

                // 额外过滤：如果你有白名单，先检查
                //if (packageNamesFilter != null && pkg !in packageNamesFilter) return@execute

                // event.source 可能为 null（被回收或权限不够）
                val source = event.source ?: return
                // 使用setOf创建单元素Set
                val singletonSet = setOf(source)
                try {

                    asyncHandle_WINDOW_CONTENT_CHANGED(source,singletonSet,pkg)
                } catch (e: Exception) {

                } finally {
                    // 必要时回收 node（不要多次调用 recycle）
                    if (Build.VERSION.SDK_INT < 34) {
                        source.recycle() // 仅旧版本需要
                    }
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

    private fun collectTextRecursively(node: AccessibilityNodeInfo, out: StringBuilder, maxLen: Int, depth: Int = 0) {
        if (out.length >= maxLen || depth > 6) return // 限制长度与深度
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            if (out.isNotEmpty()) out.append(' ')
            out.append(it)
        }

        // 避免收集可疑敏感字段 —— 例如 resource-id 包含 password、pin、otp 等（按需扩展）
        val resId = node.viewIdResourceName
        if (!resId.isNullOrBlank()) {
            val lower = resId.lowercase()
            if (listOf("password", "pin", "otp", "cvv").any { lower.contains(it) }) {
                // 遇到明确敏感的 view id，跳过该节点
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextRecursively(child, out, maxLen, depth + 1)
            child.recycle() // 回收 child 引用（注意：如果 child 与 parent 相同对象可能导致问题，谨慎使用）
            if (out.length >= maxLen) break
        }
    }



}