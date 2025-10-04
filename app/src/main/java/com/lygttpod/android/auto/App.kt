package com.lygttpod.android.auto

import android.app.Application
import com.google.android.accessibility.ext.utils.NotificationUtilXpq
import com.lygttpod.android.auto.notification.NotificationListenerServiceImp


class App : Application() {

    companion object {
        private var instance: Application? = null
        fun instance() = instance!!
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        //重新绑定服务
        NotificationUtilXpq.toggleNotificationListenerService(notificationcls = NotificationListenerServiceImp::class.java)

    }
}