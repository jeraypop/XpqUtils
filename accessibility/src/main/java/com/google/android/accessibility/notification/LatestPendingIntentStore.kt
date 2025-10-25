package com.google.android.accessibility.notification

import android.app.PendingIntent
import java.util.concurrent.atomic.AtomicReference
/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/10/26  3:58
 * Description:This is LatestPendingIntentStore
 */
object LatestPendingIntentStore {
    // 保存 Pair<key, PendingIntent> 或 null
    private val ref = AtomicReference<Pair<String, PendingIntent>?>(null)

    /** 覆盖并保存最新的一条 */
    fun saveLatest(key: String, pi: PendingIntent?) {
        if (pi == null) return
        ref.set(key to pi)
    }

    /** 读取但不删除（仅调试用） */
    fun peek(): Pair<String, PendingIntent>? = ref.get()

    /**
     * 原子地获取并清除最新的一条 — 如果已保存则返回并置空
     * 调用方在成功 send() 后无需再手动 remove
     */
    fun getAndClearLatest(): Pair<String, PendingIntent>? {
        return ref.getAndSet(null)
    }

    /** 手动清除（如果需要） */
    fun clear() {
        ref.set(null)
    }
}
