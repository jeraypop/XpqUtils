package com.google.android.accessibility.ext.utils

import android.content.Context
import android.provider.Settings
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.util.UUID

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/4/1  18:15
 * Description:This is DeviceIdProvider
 */
object DeviceIdProvider {

    private const val SP_NAME = "device_id_cache"
    private const val KEY_ANDROID_ID = "cached_android_id"

    @Volatile
    private var cachedId: String? = null

    enum class IdStatus {
        INIT,       // 首次初始化
        UNCHANGED,  // 未变化
        CHANGED     // 已变化
    }

    data class Result(
        val id: String,
        val status: IdStatus
    )

    /**
     * 统一入口：
     * - 获取稳定ID
     * - 判断是否变化
     * - 可选是否同步更新
     *
     * 必须先调用
     * 初始化
     * 检测
     * 返回稳定ID
     * 给出状态
     */
    @JvmStatic
    @JvmOverloads
    fun getId(
        context: Context = appContext,
        autoSyncIfChanged: Boolean = false
    ): Result {

        // 1️⃣ 内存缓存
        cachedId?.let {
            val status = checkChanged(context, it)
            return Result(it, status)
        }

        synchronized(this) {

            cachedId?.let {
                val status = checkChanged(context, it)
                return Result(it, status)
            }

            val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            val savedId = sp.getString(KEY_ANDROID_ID, null)

            val currentSystemId = getRawSystemAndroidId(context)

            // 2️⃣ 首次初始化
            if (savedId.isNullOrEmpty()) {
                val finalId = normalizeId(currentSystemId)

                sp.edit().putString(KEY_ANDROID_ID, finalId).apply()
                cachedId = finalId

                return Result(finalId, IdStatus.INIT)
            }

            // 3️⃣ 已存在 → 判断变化
            val normalizedCurrent = normalizeId(currentSystemId, allowEmpty = true)

            val changed = normalizedCurrent.isNotEmpty() && savedId != normalizedCurrent

            if (changed && autoSyncIfChanged) {
                // 4️⃣ 自动同步（可控）
                val newId = normalizeId(currentSystemId)

                sp.edit().putString(KEY_ANDROID_ID, newId).apply()
                cachedId = newId

                return Result(newId, IdStatus.CHANGED)
            }

            cachedId = savedId
            return Result(savedId, if (changed) IdStatus.CHANGED else IdStatus.UNCHANGED)
        }
    }

    /**
     * 仅检测变化（轻量）
     */
    @JvmStatic
    @JvmOverloads
    fun isChanged(context: Context): Boolean {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val saved = sp.getString(KEY_ANDROID_ID, null) ?: return false

        val current = normalizeId(getRawSystemAndroidId(context), allowEmpty = true)
        if (current.isEmpty()) return false

        return saved != current
    }

    // -------------------------
    // 内部方法
    // -------------------------

    private fun checkChanged(context: Context, baseId: String): IdStatus {
        val current = normalizeId(getRawSystemAndroidId(context), allowEmpty = true)

        if (current.isEmpty()) return IdStatus.UNCHANGED

        return if (current != baseId) IdStatus.CHANGED else IdStatus.UNCHANGED
    }

    /**
     * 原始系统值（不做fallback）
     */
    @JvmStatic
    @JvmOverloads
    fun getRawSystemAndroidId(context: Context = appContext): String? {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    /**
     * 标准化ID：
     * - 过滤异常值
     * - 必要时生成UUID
     */
    private fun normalizeId(raw: String?, allowEmpty: Boolean = false): String {
        if (raw.isNullOrEmpty() || raw == "9774d56d682e549c") {
            return if (allowEmpty) "" else UUID.randomUUID().toString()
        }
        return raw
    }
}