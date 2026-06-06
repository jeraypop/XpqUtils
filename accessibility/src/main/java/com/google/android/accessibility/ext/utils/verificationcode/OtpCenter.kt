package com.google.android.accessibility.ext.utils.verificationcode

import android.app.Notification
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
同一个验证码
同一个包名
5秒内
只发一次
 * Description:This is OtpCenter
 */

object OtpCenter {

    private const val DUP_WINDOW = 5000L

    private val recent =
        LinkedHashMap<String, Long>()

    private val _events =
        MutableSharedFlow<OtpEvent>(
            replay = 0,
            extraBufferCapacity = 10
        )

    val events: SharedFlow<OtpEvent>
        get() = _events

    fun report(
        code: String,
        notification: Notification,
        packageName: String?,
        source: OtpSource
    ) {
        Log.e("通知是否相等", "source: "+source )
        val key = "${packageName ?: "unknown"}:$code"

        val now = System.currentTimeMillis()

        val old = recent[key]

        if (
            old != null &&
            now - old < DUP_WINDOW
        ) {
            return
        }

        recent[key] = now

        cleanup(now)

        _events.tryEmit(
            OtpEvent(
                code = code,
                packageName = packageName,
                source = source,
                timestamp = now
            )
        )
    }

    private fun cleanup(now: Long) {

        val iterator =
            recent.entries.iterator()

        while (iterator.hasNext()) {

            val item =
                iterator.next()

            if (
                now - item.value >
                DUP_WINDOW
            ) {
                iterator.remove()
            }
        }
    }
}

enum class OtpSource(
    val priority: Int
) {
    ACCESSIBILITY(60),
    NOTIFICATION(80)
}

data class OtpEvent(
    val code: String,
    val packageName: String?,
    val source: OtpSource,
    val timestamp: Long = System.currentTimeMillis()
)