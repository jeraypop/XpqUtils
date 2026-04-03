package com.google.android.accessibility.ext.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/4/3  10:09
 * Description:This is T3Sdk
 */
object T3Sdk {

    private const val KEY_FLAG = "t3_flag"

    const val URL_T3DATA = "https://w.t3data.net/"
    const val URL_T3YANZ = "https://w.t3yanzheng.com/"

    @Volatile
    private var useData: Boolean = true // 默认

    private lateinit var context: Context

    // 初始化（必须调用）
    @JvmStatic
    @JvmOverloads
    fun init(ctx: Context = appContext, flag: Boolean? = null) {
        context = ctx.applicationContext

        useData = flag
            ?: getSp().getBoolean(KEY_FLAG, true)
    }

    // 获取当前 baseUrl
    @JvmStatic
    @JvmOverloads
    fun getBaseUrl(): String {
        return if (useData) URL_T3DATA else URL_T3YANZ
    }
    // ✅ 获取当前开关状态（给 UI 用）
    fun isUseData(): Boolean {
        return useData
    }

    // 动态切换（核心）
    @JvmStatic
    @JvmOverloads
    fun setUseData(flag: Boolean) {
        if (useData == flag) return // 避免重复写
        useData = flag
        getSp().edit().putBoolean(KEY_FLAG, flag).apply()
    }

    private fun getSp(): SharedPreferences {
        check(::context.isInitialized) { "T3Sdk not initialized" }
        return context.getSharedPreferences("t3_sdk", Context.MODE_PRIVATE)
    }
}