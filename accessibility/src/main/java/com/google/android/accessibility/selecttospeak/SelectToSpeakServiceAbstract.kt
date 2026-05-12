package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.AssistsServiceListener
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.KeyguardUnLock

import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.NetworkHelperFullSmart
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.getAllSortedMessagingStyleByTime
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateCallback
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateReceiver
import com.google.android.accessibility.ext.utils.broadcastutil.BroadcastOwnerType
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager.CHANNEL_SCREEN
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager.screenFilter
import com.google.android.accessibility.ext.window.AssistsWindowManager
import com.google.android.accessibility.ext.window.ClickIndicatorManager
import com.google.android.accessibility.ext.window.SwipeTrajectoryIndicatorManager
import com.google.android.accessibility.notification.AccessibilityNInfo
import com.google.android.accessibility.notification.AppExecutors
import com.google.android.accessibility.notification.MessageStyleInfo
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract.Companion.getAppName
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract.Companion.isTitleAndContentEmpty
import java.util.concurrent.ConcurrentHashMap

/**
* 无障碍服务基类
* 自动管理所有动态广播
* */

val accessibilityServiceLiveData = MutableLiveData<AccessibilityService?>(null)
val accessibilityService: AccessibilityService? get() = accessibilityServiceLiveData.value
abstract class SelectToSpeakServiceAbstract : AccessibilityService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    // 不再使用 ServiceLifecycleDispatcher
    // AccessibilityService 不属于标准 startService 生命周期
    // 使用 LifecycleRegistry 更稳定、更可控
    private val lifecycleRegistry = LifecycleRegistry(this)
    @Suppress("LeakingThis")
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()
    // 防止重复 dispatch destroy
    @Volatile
    private var destroyed = false

    //=====================

    private val TAG = this::class.java.simpleName
    //用于TYPE_WINDOW_CONTENT_CHANGED的简单防抖（按包名）
    //ConcurrentHashMap 创建一个 线程安全的 HashMap  在多线程环境下同时读写，不需要自己加锁
    private val lastWindowContentHandledAt = ConcurrentHashMap<String, Long>()
    private var WINDOW_CONTENT_DEBOUNCE_MS = 300L // 每个包300ms内只处理一次（根据需要调节）

    @Volatile
    private var lastEventTime: Long = 0L
    // 如果你只对特定包感兴趣，可以在这里维护白名单/黑名单
    private val packageNamesFilter: Set<String>? = null // e.g. setOf("com.whatsapp", "com.tencent.mm")



    open fun targetPackageName(): String {
        return ""
    }


    open fun asyncHandleAccessibilityNotification(notification: Notification, title: String, content: String, a_n_Info: AccessibilityNInfo){}
    open fun asyncHandleAccessibilityEvent(event: AccessibilityEvent){}
    open fun asyncHandle_WINDOW_STATE_CHANGED(data: XPQEventData){}
    open fun asyncHandle_WINDOW_CONTENT_CHANGED(data: XPQEventData){}

    open fun asyncHandle_VIEW_SCROLLED(data: XPQEventData){}
    open fun asyncHandle_WINDOWS_CHANGED(event: AccessibilityEvent){}
    open fun onSetOverlay(){}
    @CallSuper
    override fun onCreate() {

        savedStateRegistryController.performRestore(null)

        super.onCreate()

        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_CREATE
        )
    }
    @CallSuper
    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_START
        )
        try {
            onSetOverlay()
        } catch (t: Throwable) { }

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
        //serviceInfo = info

        if (AliveUtils.getKeepAliveByNotification()){
            //前台保活服务   如果放在子类中 可传入 class了
            AliveUtils.keepAliveByNotification_CLS(this,true,null)
        }
        AliveUtils.keepAliveByFloatingWindow(this,AliveUtils.getKeepAliveByFloatingWindow())


        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)    // 息屏
            addAction(Intent.ACTION_SCREEN_ON)     // 亮屏（可选）
            addAction(Intent.ACTION_USER_PRESENT)  // 解锁完成
        }

        //registerReceiver(screenReceiver, filter)

        // 创建匿名内部类实现 ScreenStateCallback 接口
        val screenStateCallback = object : ScreenStateCallback {
            override fun onScreenOff() {
                // 1️⃣ 屏幕熄灭
                // 一定 = 锁屏即将发生 / 已发生
                Log.e("监听屏幕啊", "Accessibility屏幕已关闭" )
            }

            override fun onScreenOn() {
                // 2️⃣ 屏幕点亮
                // ⚠️ 仍然可能在锁屏界面
                Log.e("监听屏幕啊", "Accessibility屏幕点亮" )
            }

            override fun onUserPresent() {
                // 3️⃣ 真正解锁完成（最重要）
                //disableKeyguard后,接收不到这个广播
                Log.e("监听屏幕啊", "Accessibility真正解锁完成" )
            }
        }
        UnifiedBroadcastManager.register(
            channel = CHANNEL_SCREEN,
            owner = this,
            ownerType = BroadcastOwnerType.ACCESSIBILITY_SERVICE,
            context = this,
            receiver = ScreenStateReceiver(screenStateCallback),
            filter = screenFilter
        )


    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        instance = this
        dealEvent(event)
        AppExecutors.executors4.execute {
            asyncHandleAccessibilityEvent(event)
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
        //手动在设置中关闭 走这里
        instance = null
        accessibilityServiceLiveData.value = null
        return super.onUnbind(intent)
    }
    override fun onInterrupt() {
        runCatching { listeners.forEach { it.onInterrupt() } }
    }


    @CallSuper
    override fun onDestroy() {
        if (destroyed) {
            super.onDestroy()
            return
        }
        destroyed = true

        lifecycleRegistry.handleLifecycleEvent(
            Lifecycle.Event.ON_DESTROY
        )

        // 清理 ViewModel
        viewModelStore.clear()

        Log.e("监听屏幕啊", "无障碍服务：onDestroy" )
        instance = null
        accessibilityServiceLiveData.value = null
        if (AliveUtils.getKeepAliveByNotification()){
            //前台保活服务   如果放在子类中 可传入 class了
            AliveUtils.keepAliveByNotification_CLS(this,false,null)
        }
        AliveUtils.keepAliveByFloatingWindow(this,false)
        //释放 clickScope
        KeyguardUnLock.release()
        try {
            //unregisterReceiver(screenReceiver)
        }catch (e: Exception){}

        super.onDestroy()
        UnifiedBroadcastManager.unregister(
            channel = CHANNEL_SCREEN,
            owner = this,
            context = this
        )
        ClickIndicatorManager.cleanup()
        SwipeTrajectoryIndicatorManager.cleanup()
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

        @Volatile
        var cur_PkgName: String? = ""
        @JvmStatic
        fun copyNodeCompat(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            return try {
                if (Build.VERSION.SDK_INT >= 34) {
                    AccessibilityNodeInfo(node)
                } else {
                    AccessibilityNodeInfo.obtain(node)
                }
            } catch (t: Throwable) {
                //Log.w(TAG, "copyNodeCompat failed", t)
                null
            }
        }
        @JvmStatic
        fun recycleCompat(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (Build.VERSION.SDK_INT < 34) {
                try { node.recycle() } catch (_: Throwable) { /* ignore */ }
            } else {
                // API34+ recycle 已废弃且为空实现，不必调用
                try { node.recycle() } catch (_: Throwable) { /* ignore */ }
            }
        }

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
        var messagingStyle: NotificationCompat.MessagingStyle? = null
        try {
            messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
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
                AppExecutors.executors3.execute {
                    val eventTime = event.eventTime
                    if (!shouldHandle(eventTime)) return@execute
                    val pkgName = event.packageName?.toString() ?: "UnKnown"
                    // 方式一：直接从 event.text 获取（简单但可能不完整）
                    val eventText = event.text?.joinToString(separator = " ")?.takeIf { it.isNotBlank() } ?: "（event.text 为空或不完整）"
                    // 方式二：从 parcelableData 获取 Notification（更完整）
                    val notification = event.parcelableData as? Notification ?: return@execute
                    val a_n_Info = buildAccessibilityNInfo(notification, pkgName, eventTime,eventText)
                    asyncHandleAccessibilityNotification(notification,a_n_Info.title,a_n_Info.content,a_n_Info)
                }
            }
            //状态改变
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val originalRoot = try { rootInActiveWindow } catch (_: Throwable) { null }
                originalRoot ?: return
                // 窗口变化（Activity 切换、弹窗显示等），你可以在这里处理或记录
                val pkg = originalRoot.packageName?.toString()?:return
                val ev_pkg = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""
                //屏蔽掉无关的干扰包,com.android.systemui  输入法等
                if (!TextUtils.equals(pkg, ev_pkg)) return
                cur_PkgName = pkg
                // 创建节点副本 复制 active root
                val rootCopy = copyNodeCompat(originalRoot)
                rootCopy ?: return
                val eventData = XPQEventData(
                    service = this@SelectToSpeakServiceAbstract,
                    event = event,
                    rootNode = rootCopy,
                    nodeInfoList = listOf(rootCopy),
                    pkgName = pkg,
                    className = className
                )
                AppExecutors.executors3.execute {
                    try {
                        asyncHandle_WINDOW_STATE_CHANGED(eventData)
                    } catch (t: Throwable) {

                    } finally {

                    }
                }

                /*AppExecutors.executors4.execute {
                    val nodeInfoList: MutableList<AccessibilityNodeInfo> = mutableListOf()
                    nodeInfoList.add(rootCopy)
                    // 复制 windows roots（兼容性安全调用）
                    val windows = try { getWindows() } catch (_: Throwable) { null }
                    windows?.forEach { win ->
                        val winRoot = try { win?.root } catch (_: Throwable) { null }
                        val winCopy = copyNodeCompat(winRoot)
                        winCopy?.let { nodeInfoList.add(it) }
                    }
                    if (nodeInfoList.isEmpty()) return@execute
                    try {
                        val eventData = XPQEventData(
                            service = this@SelectToSpeakServiceAbstract,
                            event = event,
                            rootNode = rootCopy,
                            nodeInfoList = nodeInfoList,
                            pkgName = pkg,
                            className = className
                        )
                        asyncHandle_WINDOW_STATE_CHANGED(eventData)
                    } catch (t: Throwable) {

                    } finally {

                    }
                }*/

                //定期更新标准的北京时间
                NetworkHelperFullSmart.updateMyTime()

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
                sourceCopy ?: return
                val eventData = XPQEventData(
                    service = this@SelectToSpeakServiceAbstract,
                    event = event,
                    rootNode = sourceCopy,
                    nodeInfoList = listOf(sourceCopy),
                    pkgName = pkg,
                    className = ""
                )
                AppExecutors.executors3.execute {
                    try {
                        asyncHandle_WINDOW_CONTENT_CHANGED(eventData)
                    } catch (t: Throwable) {

                    } finally {

                    }
                }


            }
            //滑动改变
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val pkg = event.packageName?.toString() ?: return
                val sourceOriginal = try { event.source } catch (_: Throwable) { null }
                sourceOriginal ?: return
                val sourceCopy = copyNodeCompat(sourceOriginal)
                sourceCopy ?: return
                val fromIndex = event.fromIndex
                val toIndex = event.toIndex
                val scrollx = event.scrollX
                val scrolly = event.scrollY
                val eventData = XPQEventData(
                    service = this@SelectToSpeakServiceAbstract,
                    event = event,
                    rootNode = sourceCopy,
                    nodeInfoList = listOf(sourceCopy),
                    pkgName = pkg,
                    className = "",
                    fromIndex = fromIndex,
                    toIndex = toIndex,
                    scrollx = scrollx,
                    scrolly = scrolly
                )
                AppExecutors.executors3.execute {
                    try {
                        asyncHandle_VIEW_SCROLLED(eventData)
                    } catch (t: Throwable) {

                    } finally {

                    }
                }

            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                AppExecutors.executors3.execute {
                    asyncHandle_WINDOWS_CHANGED(event)
                }
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



/*    override fun onScreenOff() {
        // 1️⃣ 屏幕熄灭
        // 一定 = 锁屏即将发生 / 已发生
        Log.e("监听屏幕啊", "屏幕已关闭" )
        if (KeyguardUnLock.getUnLockMethod()==1 && KeyguardUnLock.getAutoReenKeyguard()){
            wakeKeyguardOff(tip = "广播:屏幕已关闭")
        }
    }

    override fun onScreenOn() {
        // 2️⃣ 屏幕点亮
        // ⚠️ 仍然可能在锁屏界面
        Log.e("监听屏幕啊", "屏幕点亮" )
        if (KeyguardUnLock.getUnLockMethod()==1 && KeyguardUnLock.getAutoDisableKeyguard()){
            //禁用键盘锁
            wakeKeyguardOn(tip = "广播:屏幕已点亮")
        }
    }

    override fun onUserPresent() {
        // 3️⃣ 真正解锁完成（最重要）
        //disableKeyguard后,接收不到这个广播
        Log.e("监听屏幕啊", "真正解锁完成" )
    }*/

}

data class NodeSummary(
    val packageName: String?,
    val className: String?,
    val nodeText: String?,
    val viewId: String?,
    val bounds: Rect,
    val describe: String?
)
data class XPQEventData(
    val service: AccessibilityService,
    val event: AccessibilityEvent,
    val rootNode: AccessibilityNodeInfo,
    val nodeInfoList: List<AccessibilityNodeInfo>,
    val pkgName: String = "",
    val className: String = "",
    val fromIndex: Int = 0,
    val toIndex: Int = 0,
    val scrollx: Int = 0,
    val scrolly: Int = 0,


)