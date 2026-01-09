package com.google.android.accessibility.ext.utils

import android.content.Context
import android.content.Intent

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/1/9  14:52
 * Description:This is AppBroadcast
 */
object AppBroadcast {

    fun send(
        context: Context,
        action: String,
        block: Intent.() -> Unit = {}
    ) {
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            block()
        }
        context.sendBroadcast(intent)
    }
}
