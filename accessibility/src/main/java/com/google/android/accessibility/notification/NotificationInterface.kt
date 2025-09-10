package com.google.android.accessibility.notification

import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

interface NotificationInterface {
    /**
     * 当界面发生事件时回调，即 [AssistsService.onAccessibilityEvent] 回调
     */
    fun onNotificationPosted(sbn: StatusBarNotification) {}
    fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {}

    /**
     * 服务启用后的回调，即[AssistsService.onServiceConnected]回调
     */
    fun onListenerConnected(service: NotificationListenerServiceAbstract) {}
    fun onNotificationRemoved(sbn: StatusBarNotification) {}

    /**
     * 服务关闭后的回调，即[AssistsService.onUnbind]回调
     */
    fun onListenerDisconnected() {}
    fun onDestroy() {}


    /**
     * 录屏权限开启
     */
    fun screenCaptureEnable() {

    }
}