package com.google.android.accessibility.ext.utils

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.acc.inputText
import com.google.android.accessibility.ext.acc.inputTextPaste
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.notification.ClearNotificationListenerServiceImp
import com.google.android.accessibility.notification.MessageStyleInfo
import com.google.android.accessibility.notification.notificationService
import com.google.android.accessibility.selecttospeak.accessibilityService
import java.util.regex.Pattern

object NotificationUtilXpq {

    /*
    * 检测通知监听服务是否被授权
    * */
    @JvmOverloads
    @JvmStatic
    fun isNotificationListenersEnabled(context: Context = appContext): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    @JvmOverloads
    @JvmStatic
    fun isNotificationListenerEnabled(context: Context = appContext,
                                      listenerClass: Class<out NotificationListenerService> = ClearNotificationListenerServiceImp::class.java
    ): Boolean {
        val pkgName = context.packageName
        val flatName = ComponentName(pkgName, listenerClass.name).flattenToString()

        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledListeners)

        while (colonSplitter.hasNext()) {
            val component = colonSplitter.next()
            if (component.equals(flatName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }


    /**
     * 检测通知发送服务是否被授权
     * @param context
     * @return
     */
    @JvmOverloads
    @JvmStatic
    fun isNotificationEnabled(context: Context = appContext): Boolean {
        return NotificationManagerCompat.from(context.getApplicationContext())
            .areNotificationsEnabled()
    }
    /*
    * 打开辅助服务设置页面
    * */
    @JvmOverloads
    @JvmStatic
    fun gotoAccessibilitySetting(context: Context = appContext) {
        AliveUtils.closeTaskHidePlus()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    /*
    * 检测无障碍服务是否被授权
    * */
    @JvmOverloads
    @JvmStatic
    fun isAccessibilityEnabled(context: Context = appContext): Boolean {
        return accessibilityService != null
    }
    /*
    * 打开通知监听设置页面
    * */
    @JvmOverloads
    @JvmStatic
    fun gotoNotificationAccessSetting(context: Context = appContext): Boolean {
        AliveUtils.closeTaskHidePlus()
        return try {
            var action: String =""
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                action =  Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            } else {
                action =  "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
            }
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) { //普通情况下找不到的时候需要再特殊处理找一次
            try {
                val intent = Intent()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val cn = ComponentName("com.android.settings", "com.android.settings.Settings\$NotificationAccessSettingsActivity")
                intent.component = cn
                intent.putExtra(":settings:show_fragment", "NotificationAccessSettings")
                context.startActivity(intent)
                return true
            } catch (e1: Exception) {
                e1.printStackTrace()
            }
            Toast.makeText(context, "对不起，您的手机暂不支持", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
            false
        }
    }


    /**
     *
     *  在退出app后，再次打开，监听不生效，这个时候我们需要做一些处理。在app启动时，我们去重新关闭打开一次监听服务，让它正常工作。
     *
     *
     *  该方法使用前提是 NotificationListenerService 已经被用户授予了权限，否则无效。
     *  另外，在自己的小米手机上实测，重新完成 rebind 操作需要等待 10 多秒（我的手机测试过大概在 13 秒左右）。
     *  幸运的是，官方也已经发现了这个问题，在 API 24 中提供了
     *  requestRebind(ComponentName componentName) 方法来支持重新绑定。
     *
     * 作者：俞其荣
     * 链接：https://www.jianshu.com/p/981e7de2c7be
     * 来源：简书
     * 著作权归作者所有。商业转载请联系作者获得授权，非商业转载请注明出处。
     *
     * 重新启动服务的触发时机：
     * 1、后台另一个服务或定时任务间隔执行（强烈不建议）；
     * 2、进入UI时（或Application创建时）执行。
     */
    @JvmOverloads
    @JvmStatic
    fun toggleNotificationListenerService(context: Context = appContext, notificationCls: Class<out NotificationListenerService> ) {
        val runnable = Runnable {
            try {
                val pm = context.packageManager
                val cn = ComponentName(context, notificationCls)

                // 先 disable 再 enable，强制系统刷新组件状态
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )

                // Android N+ 可请求重绑定
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        NotificationListenerService.requestRebind(cn)
                    } catch (e: Exception) {
                        // 有些厂商/ROM 在这里可能抛异常，捕获以免崩溃

                    }
                }

                //Log.d(TAG, "rebindNotificationListener executed on thread: ${Thread.currentThread().name}")
            } catch (e: Exception) {
                //Log.e(TAG, "rebindNotificationListenerSafe error", e)
            }
        }

        // 如果已经在主线程则直接执行，否则 post 到主线程
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            Handler(Looper.getMainLooper()).post(runnable)
        }


    }



    // 在调用 getActiveNotifications() 的地方进行包裹
    fun safeGetActiveNotifications(service: NotificationListenerService? = notificationService): Array<StatusBarNotification>? {
        try {
            if (service == null) return null
            // 尝试获取通知
            return service.activeNotifications
            // 或者 super.getActiveNotifications()
        } catch (e: SecurityException) {
            // 系统认为监听器未连接
            Log.e("MicroX", "Error fetching notifications: Service not bound/allowed", e)
        } catch (e: IllegalStateException) {
            // 某些旧版本可能会抛出此异常
            Log.e("MicroX", "Error fetching notifications: Service not connected", e)
        } catch (e: Exception) {
            // 捕获其他潜在的 IPC 错误
            Log.e("MicroX", "Unknown error in getActiveNotifications", e)
        }
        return null
    }


    /**
     * 从 activeNotifications 中获取最新的一条通知
     */
    fun getLatestNotification(notifications: Array<StatusBarNotification>?): StatusBarNotification? {
        if (notifications.isNullOrEmpty()) return null
        return notifications.maxByOrNull { it.postTime }
    }
    /**
     * 从 activeNotifications 中获取时间第二晚的通知
     */
    fun getSecondLatestNotification(notifications: Array<StatusBarNotification>?): StatusBarNotification? {
        if (notifications.isNullOrEmpty()) return null
        return notifications.sortedByDescending { it.postTime }.getOrNull(1)
    }

    /**
     * 从 activeNotifications 中获取所有通知，并按时间从新到旧排序
     */
    fun getAllSortedByTime(notifications: Array<StatusBarNotification>?): List<StatusBarNotification> {
        return notifications?.sortedByDescending { it.postTime } ?: emptyList()
    }
    /**
     * 从 MessagingStyle 中获取所有会话通知，并按时间从新到旧排序
     */
    fun getAllSortedMessagingStyleByTime(notifications: List<NotificationCompat.MessagingStyle.Message>?): List<NotificationCompat.MessagingStyle.Message> {
        return notifications?.sortedByDescending { it.timestamp } ?: emptyList()
    }


    @JvmStatic
    fun getNotificationData11(extraszhedie: Bundle?): List<String> {
        if (extraszhedie == null)return listOf(
            appContext.getString(
            R.string.notificationtitlenull), appContext.getString(
            R.string.notificationcontentnull))
        val msgList = mutableListOf<String>()
        var title:String=""
        var content:String=""
        //1
        var texttitle = extraszhedie.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        var textcontent = extraszhedie.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        if (TextUtils.isEmpty(texttitle) && TextUtils.isEmpty(textcontent)){
            //2
            texttitle = extraszhedie.getCharSequence(Notification.EXTRA_TITLE) ?: ""
            textcontent = extraszhedie.getCharSequence(Notification.EXTRA_TEXT) ?: ""
            //---------------------------
            if (TextUtils.isEmpty(texttitle)&& TextUtils.isEmpty(textcontent)){
                //3
                texttitle = extraszhedie.getCharSequence(Notification.EXTRA_TITLE) ?: ""
                textcontent = extraszhedie.getCharSequence(Notification.EXTRA_TEXT) ?: ""
                //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
                if (TextUtils.isEmpty(texttitle)&& TextUtils.isEmpty(textcontent)){
                    //4
                    title = extraszhedie!!.getString(
                        Notification.EXTRA_TITLE, appContext.getString(
                        R.string.notificationtitlenull))
                    content = extraszhedie!!.getString(Notification.EXTRA_TEXT, appContext.getString(R.string.notificationcontentnull))




                }else{
                    //此处必然会有一个不为空
                    if (!TextUtils.isEmpty(texttitle)){
                        title = texttitle.toString()
                    }else{
                        texttitle = extraszhedie.getCharSequence(Notification.EXTRA_TITLE) ?: ""
                        if (!TextUtils.isEmpty(texttitle)){
                            title = texttitle.toString()
                        }else{
                            title = extraszhedie!!.getString(
                                Notification.EXTRA_TITLE, appContext.getString(
                                R.string.notificationtitlenull))
                        }
                    }

                    if (!TextUtils.isEmpty(textcontent)){
                        content = textcontent.toString()
                    }else{
                        textcontent = extraszhedie.getCharSequence(Notification.EXTRA_TEXT) ?: ""
                        if (!TextUtils.isEmpty(textcontent)){
                            content = textcontent.toString()
                        }else{
                            content = extraszhedie.getString(Notification.EXTRA_TEXT, appContext.getString(R.string.notificationcontentnull))
                        }
                    }


                }
                //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++


            }else{
                //此处必然会有一个不为空
                if (!TextUtils.isEmpty(texttitle)){
                    title = texttitle.toString()
                }else{
                    texttitle = extraszhedie.getCharSequence(Notification.EXTRA_TITLE) ?: ""
                    if (!TextUtils.isEmpty(texttitle)){
                        title = texttitle.toString()
                    }else{
                        title = extraszhedie!!.getString(
                            Notification.EXTRA_TITLE, appContext.getString(
                            R.string.notificationtitlenull))
                    }
                }

                if (!TextUtils.isEmpty(textcontent)){
                    content = textcontent.toString()
                }else{
                    textcontent = extraszhedie.getCharSequence(Notification.EXTRA_TEXT) ?: ""
                    if (!TextUtils.isEmpty(textcontent)){
                        content = textcontent.toString()
                    }else{
                        content = extraszhedie.getString(Notification.EXTRA_TEXT, appContext.getString(R.string.notificationcontentnull))
                    }
                }


            }
            //---------------------------

        }else{
            //此处必然会有一个不为空
            if (!TextUtils.isEmpty(texttitle)){
                title = texttitle.toString()
            }else{
                texttitle = extraszhedie.getCharSequence(Notification.EXTRA_TITLE) ?: ""
                if (!TextUtils.isEmpty(texttitle)){
                    title = texttitle.toString()
                }else{
                    title = extraszhedie!!.getString(
                        Notification.EXTRA_TITLE, appContext.getString(
                        R.string.notificationtitlenull))
                }
            }

            if (!TextUtils.isEmpty(textcontent)){
                content = textcontent.toString()
            }else{
                textcontent = extraszhedie.getCharSequence(Notification.EXTRA_TEXT) ?: ""
                if (!TextUtils.isEmpty(textcontent)){
                    content = textcontent.toString()
                }else{
                    content = extraszhedie.getString(Notification.EXTRA_TEXT, appContext.getString(R.string.notificationcontentnull))
                }
            }


        }


        msgList.add(title)
        msgList.add(content)
        return msgList.take(2)
    }
    /**
     * 解析通知标题和内容  不包含会话通知
     */
    @JvmStatic
    fun getNotificationData(extras: Bundle?): List<String> {
        // 如果 extras 是 null，直接返回默认值
        if (extras == null) {
            return listOf(
                appContext.getString(R.string.notificationtitlenull),
                appContext.getString(R.string.notificationcontentnull)
            )
        }

        // 获取标题和内容的辅助函数，返回非空非空白的字符串
        fun getStringOrFallback(key: String, fallback: String): String {
            return extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
                ?: extras.getString(key, fallback)
                ?: fallback
        }

        // 获取标题
        val title = getStringOrFallback(Notification.EXTRA_TITLE, appContext.getString(R.string.notificationtitlenull))

        // 获取内容，如果 EXTRA_TEXT 为空，则尝试获取 EXTRA_BIG_TEXT
        val content = getStringOrFallback(Notification.EXTRA_TEXT,
            getStringOrFallback(Notification.EXTRA_BIG_TEXT, appContext.getString(R.string.notificationcontentnull))
        )

        return listOf(title, content)
    }
    /**
     * 解析通知标题和内容  包含会话通知
     */
    @JvmStatic
    fun getNotificationData(extras: Bundle?, notification: Notification): List<String> {
        // 如果 extras 是 null，直接返回默认值
        if (extras == null) {
            return listOf(
                appContext.getString(R.string.notificationtitlenull),
                appContext.getString(R.string.notificationcontentnull)
            )
        }

        // 获取标题和内容的辅助函数，返回非空非空白的字符串
        fun getStringOrFallback(key: String, fallback: String): String {
            return extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
                ?: extras.getString(key, fallback)
                ?: fallback
        }

        // 获取标题
        val title = getStringOrFallback(Notification.EXTRA_TITLE, appContext.getString(R.string.notificationtitlenull))

        // 获取内容，如果 EXTRA_TEXT 为空，则尝试获取 EXTRA_BIG_TEXT
        val content = getStringOrFallback(Notification.EXTRA_TEXT,
            getStringOrFallback(Notification.EXTRA_BIG_TEXT, appContext.getString(R.string.notificationcontentnull))
        )

        // 判断是否为 MessagingStyle 类型的通知
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)

        // 如果是 MessagingStyle 类型的通知，解析该类型的标题和内容
        if (messagingStyle != null) {
            // 获取对话标题（例如联系人名称或群聊名称）
            val conversationTitle: String = (messagingStyle.conversationTitle ?: appContext.getString(R.string.notificationtitlenull)).toString()

            // 获取消息列表
            val messageList = messagingStyle?.messages?:emptyList()
            // 获取所有按时间排序messagingStyle的消息列表
            val sortedmessageList = getAllSortedMessagingStyleByTime(messageList)
            //获取最新的一条消息
            // sortedmessageList?.firstOrNull()
            // sortedmessageList.getOrNull(0) 两个是等价的
            val first_msg = sortedmessageList?.firstOrNull()
            first_msg?:return listOf(
                appContext.getString(R.string.notificationtitlenull),
                appContext.getString(R.string.notificationcontentnull)
            )
            val firstMessage = first_msg?.let {
                MessageStyleInfo(
                    timestamp = it.timestamp,
                    title = conversationTitle,
                    sender = it.person?.toString() ?: "Unknown",
                    text = it.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: appContext.getString(R.string.notificationcontentnull)
                )
            } ?: MessageStyleInfo( //默认值
                timestamp = 0L,
                title = appContext.getString(R.string.notificationtitlenull),
                sender = "Unknown",
                text = appContext.getString(R.string.notificationcontentnull)
            )
            val content =  if (TextUtils.equals(appContext.getString(R.string.notificationcontentnull), firstMessage.text)){
                firstMessage.text
            }else{
                firstMessage.sender + ":" + firstMessage.text
            }
            val titile =  firstMessage.title
            // 返回对话标题和最新消息内容
            return listOf(titile, content)
        }

        // 返回默认值
        return listOf(title, content)
    }

    //验证码生成工具
    @JvmOverloads
    @JvmStatic
    fun generateCode(length: Int = 6): String {
        val chars = "0123456789"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

   //创建通知渠道（只需一次）
   const val YANZHENGMA_CHANNEL_ID = "debug_xpq_code_channel"

    @JvmOverloads
    @JvmStatic
    fun ensureNotificationChannel(context: Context = appContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                YANZHENGMA_CHANNEL_ID,
                "验证码通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于模拟验证码"
            }

            manager.createNotificationChannel(channel)
        }
    }
    @JvmOverloads
    @JvmStatic
    fun sendNotification(context: Context=appContext,
                         code: String = generateCode(),
                         title: String = "通知验证码",
                         content: String = "你的登录验证码是：$code\n5 分钟内有效",
                         canCancel: Boolean = true,
                         pendingIntent: PendingIntent? = null
                         ) {

        ensureNotificationChannel(context)

        val notification = NotificationCompat.Builder(context, YANZHENGMA_CHANNEL_ID)
            .setSmallIcon(getHostAppIcon())
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(content)
            )
            .setAutoCancel(canCancel)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (pendingIntent != null) {
            notification.setContentIntent(pendingIntent)
        }
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        NotificationManagerCompat.from(context)
            .notify(code.hashCode(), notification.build())
    }

    @JvmOverloads
    @JvmStatic
    fun getHostAppIcon(context: Context = appContext): Int {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)
            applicationInfo.icon
        } catch (e: PackageManager.NameNotFoundException) {
            // 如果找不到，则使用默认图标
            android.R.drawable.ic_dialog_alert
        }
    }

    @JvmOverloads
    @JvmStatic
    fun editPaste(str: String,accService: AccessibilityService? = accessibilityService,byClipboard: Boolean = false) {
        accService ?: return
        val accessibilityNodeInfo = getNodeInfo(accService.rootInActiveWindow, str)
        accessibilityNodeInfo ?: return
        //节点文本不为空
        accessibilityNodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val parent = accessibilityNodeInfo.parent
        if (parent != null) {
            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        accessibilityNodeInfo.inputTextPaste(byClipboard,str)
        recycleCompat(accessibilityNodeInfo)
    }
    private fun recycleCompat(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < 34) {
            try { node.recycle() } catch (_: Throwable) { /* ignore */ }
        } else {
            // API34+ recycle 已废弃且为空实现，不必调用
        }
    }
    @JvmOverloads
    @JvmStatic
    fun copyToClipboard(text: String, context: Context? = appContext) {
        context ?: return
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.setPrimaryClip(ClipData.newPlainText("复制", text))
    }

    @JvmOverloads
    @JvmStatic
    fun getNodeInfo(accessibilityNodeInfo: AccessibilityNodeInfo?, str: String): AccessibilityNodeInfo? {
        if (accessibilityNodeInfo == null) {
            return null
        }
        if ("com.chinamworld.bocmbci" == accessibilityNodeInfo.packageName) {
            return null
        }

        val className = accessibilityNodeInfo.className?.toString() ?: ""
        val text = accessibilityNodeInfo.text?.toString() ?: ""
        val hintText = accessibilityNodeInfo.hintText?.toString() ?: ""

        val inputType = accessibilityNodeInfo.inputType

        Log.e("自动粘贴", "ClassName=$className")
        Log.e("自动粘贴", "Text= $text hintText= $hintText inputType= $inputType")

        if (className.contains("EditText") || className.contains("AutoCompleteTextView")) {
            if (text.length == 0 ||
                text.contains("验证码") ||
                text.contains("授权码") ||
                text.contains("随机码") ||
                text.contains("校验码") ||
                (text.contains("动态")&&text.contains("码")) ||
                text.contains("短信") ||
                accessibilityNodeInfo.maxTextLength == 4 ||
                accessibilityNodeInfo.maxTextLength == 6 ||
                hintText.contains("验证码") ||
                hintText.contains("授权码") ||
                hintText.contains("随机码") ||
                hintText.contains("校验码") ||
                (hintText.contains("动态")&&hintText.contains("码")) ||
                hintText.contains("短信")
            ) {
                val verificationKeywords = listOf("图形验证码", "图片验证码", "图画验证码")
                if (verificationKeywords.none { hintText.contains(it) }) {
                    return accessibilityNodeInfo
                }

            }

            if (text == str && accessibilityNodeInfo.isClickable) {
                return accessibilityNodeInfo
            }

            val parent = accessibilityNodeInfo.parent
            if (parent != null) {
                if (text == str && parent.isClickable) {
                    return parent
                }
            }
        }

        if (className.contains("TextView")) {
            if (text == str && accessibilityNodeInfo.isClickable) {
                accessibilityNodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.e("自动粘贴", "Text=str$text")
            }

            val parent = accessibilityNodeInfo.parent
            if (parent != null) {
                if (text == str && parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.e("自动粘贴", "Text2=str$text")
                }
            }
        }

        for (i in 0 until accessibilityNodeInfo.childCount) {
            val nodeInfo = getNodeInfo(accessibilityNodeInfo.getChild(i), str)
            if (nodeInfo != null) {
                return nodeInfo
            }
        }
        return null
    }

    @JvmStatic
    fun extractVerificationCode(content: String): Pair<Boolean, String?> {
        // 验证码关键词检测
        val verificationKeywords = listOf(
            "验证码", "授权码", "随机码", "动态密码", "校验码", "内有效", "完成验证"
        )

        if (!verificationKeywords.any { content.contains(it) }) {
            return Pair(false, null)
        }

        val cleanContent = content.replace(" ", "")
        val patterns = arrayOf(
            "(?<=码(|是|为|：|:|是：|是:|为：|为:))(\\d{4,6})",
            "((?<=\\D)(\\d{4,6})(?=\\D))"
        )

        for (pattern in patterns) {
            val matcher = Pattern.compile(pattern).matcher(cleanContent)
            if (matcher.find()) {
                val verificationCode = matcher.group(0)
                return Pair(true, verificationCode)
            }
        }

        return Pair(false, null)
    }

    data class PackageCodeResult(
        val found: Boolean = false,
        val code: String? = null,
        val codeType: String = "快递取件码",
        val content: String = "",
        val pendingIntent: PendingIntent? = null
    )
    @JvmOverloads
    @JvmStatic
    fun extractPackageCode(content: String, pendingIntent: PendingIntent? = null): PackageCodeResult {
        // 检测是否包含取件码关键词
        if (!content.contains("取件码") && !content.contains("取件碼")) {
            return PackageCodeResult(found = false)
        }

        // 尝试匹配取件码
        val code = findPackageCode(content)

        return if (code != null) {
            // 找到取件码，执行处理逻辑
            val codeType = when {
                content.contains("菜鸟智能柜") || content.contains("菜鳥智能櫃") -> "菜鸟取件码"
                content.contains("丰巢") || content.contains("豐巢") -> "丰巢取件码"
                else -> "快递取件码"
            }
            PackageCodeResult(found = true, code = code,content = content, codeType = codeType,pendingIntent = pendingIntent)
        } else {
            PackageCodeResult(found = false)
        }
    }
    @JvmOverloads
    @JvmStatic
    fun findPackageCode(content: String): String? {
        var regex = "[0-9]{8}".toRegex()
        var all = regex.findAll(content)
        var allList = all.toList()

        if (allList.isEmpty()) {
            regex = "[0-9]{6}".toRegex()
            all = regex.findAll(content)
            allList = all.toList()
        }

        return if (allList.isNotEmpty()) {
            allList.first().value
        } else {
            null
        }
    }







}