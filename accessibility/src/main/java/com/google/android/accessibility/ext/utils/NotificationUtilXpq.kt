package com.google.android.accessibility.ext.utils

import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.AliveUtils.closeTaskHidePlus
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.notification.ClearNotificationListenerServiceImp
import com.google.android.accessibility.notification.MessageStyleInfo

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
    fun toggleNotificationListenerService(context: Context = appContext, notificationcls: Class<out NotificationListenerService> ) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(ComponentName(context, notificationcls),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(ComponentName(context, notificationcls),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            NotificationListenerService.requestRebind(ComponentName(context, notificationcls))
        } else {

        }


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






}