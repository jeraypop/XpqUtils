package com.google.android.accessibility.ext.utils.broadcastutil

import android.content.BroadcastReceiver
import android.content.Context

/**
 * 单个广播通道的运行状态
 * 内部使用，不对外暴露
 * 内部状态模型
 */
internal class ManagedBroadcast(
    val channel: String
) {
    var owner: Any? = null
    var ownerType: BroadcastOwnerType = BroadcastOwnerType.NONE
    var receiver: BroadcastReceiver? = null
    var context: Context? = null
}