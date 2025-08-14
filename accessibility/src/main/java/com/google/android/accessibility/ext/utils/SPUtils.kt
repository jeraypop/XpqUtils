package com.google.android.accessibility.ext.utils

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/7/10  15:45
 * Description:This is SPUtils
 */

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes

object SPUtils {

    private var defaultSharedPreferences: SharedPreferences? = null

    /**
     * 初始化默认的 SharedPreferences
     * @param context 上下文
     * 建议在 Application 中调用
     */
    fun init(context: Context) {
        defaultSharedPreferences = context.getSharedPreferences(
            context.packageName,
            Context.MODE_PRIVATE
        )
    }

    /**
     * 获取指定名称的 SharedPreferences
     * @param context 上下文
     * @param name 文件名
     * @return SharedPreferences 实例
     */
    fun getSharedPreferences(context: Context, name: String): SharedPreferences {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /**
     * 获取默认的 SharedPreferences
     * @return 默认 SharedPreferences 实例
     */
    private val defaultSP: SharedPreferences
        get() {
            check(defaultSharedPreferences != null) { "SPUtils 未初始化，请先调用 init()" }
            return defaultSharedPreferences!!
        }

    // === 存储方法 ===

    fun putString(key: String, value: String?) {
        defaultSP.edit().putString(key, value).apply()
    }

    fun putInt(key: String, value: Int) {
        defaultSP.edit().putInt(key, value).apply()
    }

    fun putBoolean(key: String, value: Boolean) {
        defaultSP.edit().putBoolean(key, value).apply()
    }

    fun putFloat(key: String, value: Float) {
        defaultSP.edit().putFloat(key, value).apply()
    }

    fun putLong(key: String, value: Long) {
        defaultSP.edit().putLong(key, value).apply()
    }

    fun putStringSet(key: String, value: Set<String>?) {
        defaultSP.edit().putStringSet(key, value).apply()
    }

    // === 读取方法 ===

    fun getString(key: String, defaultValue: String? = null): String? {
        return defaultSP.getString(key, defaultValue)
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return defaultSP.getInt(key, defaultValue)
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return defaultSP.getBoolean(key, defaultValue)
    }

    fun getFloat(key: String, defaultValue: Float = 0f): Float {
        return defaultSP.getFloat(key, defaultValue)
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return defaultSP.getLong(key, defaultValue)
    }

    fun getStringSet(key: String, defaultValue: Set<String>? = null): Set<String>? {
        return defaultSP.getStringSet(key, defaultValue)
    }

    // === 删除与清除 ===

    fun remove(key: String) {
        defaultSP.edit().remove(key).apply()
    }

    fun clear() {
        defaultSP.edit().clear().apply()
    }

    fun contains(key: String): Boolean {
        return defaultSP.contains(key)
    }

    // === 多进程支持 ===

    fun getMultiProcessSharedPreferences(context: Context, name: String): SharedPreferences {
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
        } else {
            Context.MODE_PRIVATE
        }
        return context.getSharedPreferences(name, mode)
    }
}
