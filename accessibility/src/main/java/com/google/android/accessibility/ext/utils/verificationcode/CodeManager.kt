package com.google.android.accessibility.ext.utils.verificationcode

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.verificationcode.CodeToastManager.showCodeFloatToast
import java.util.concurrent.ConcurrentHashMap

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/4/24  2:19
 * Description:This is ToastManager
 */
object CodeManager {

    private val handler = Handler(Looper.getMainLooper())
    private const val SP_FILE_NAME = "sp_name_wxrp"
    private val sp = appContext.getSharedPreferences(SP_FILE_NAME, Context.MODE_PRIVATE)
    private val lastTriggerMap = ConcurrentHashMap<Any, Long>()
    private const val DEFAULT_THROTTLE_MS = 1000L

    /**
     * 显示 Toast，默认 1 秒频率控制
     */
    @JvmStatic
    fun showCodeToast(trigger: Any, content: String,packageName: String? = null) {
        showCodeToast(trigger, content, packageName, DEFAULT_THROTTLE_MS)
    }

    /**
     * 显示 Toast，自定义频率，自动清理缓存
     */
    @JvmStatic
    fun showCodeToast(trigger: Any, content: String,packageName: String? = null, throttleMs: Long) {
        if (content.isEmpty()) return

        val now = System.currentTimeMillis()
        val lastTime = lastTriggerMap[trigger]
        if (lastTime != null && now - lastTime < throttleMs) return

        lastTriggerMap[trigger] = now

        handler.post {
            showCodeFloatToast(message = content, packageName = packageName)
        }

        // throttleMs 后自动清理 trigger
        handler.postDelayed({ lastTriggerMap.remove(trigger) }, throttleMs)
    }

    /**
     * 可选：手动清理缓存
     */
    @JvmStatic
    fun clearCodeAllCache() {
        lastTriggerMap.clear()
    }
}