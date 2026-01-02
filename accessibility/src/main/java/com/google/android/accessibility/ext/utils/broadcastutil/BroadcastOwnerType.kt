package com.google.android.accessibility.ext.utils.broadcastutil

/**
 * 广播拥有者类型 + 优先级
 * 数值越大，优先级越高
 * （优先级定义，唯一入口）
 */
enum class BroadcastOwnerType(val priority: Int) {

    NONE(0),

    ACTIVITY(1),

    SERVICE(2),

    NOTIFICATION_SERVICE(3),

    ACCESSIBILITY_SERVICE(4)
}