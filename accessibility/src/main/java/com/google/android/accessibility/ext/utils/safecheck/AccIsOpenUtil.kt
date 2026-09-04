package com.google.android.accessibility.ext.utils.safecheck

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * Company    : 
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/9/4  4:54
 * Description:This is AccIsOpenUtil
 */
object AccIsOpenUtil {
    private const val TAG = "AccIsOpenUtil"

    private val DEFAULT_WHITE_LIST = listOf(
        "com.google.android.accessibility.selecttospeak.SelectToSpeakService",
        "com.dianming.phoneapp.MyAccessibilityService",
    )
    private val remoteWhiteList = mutableListOf<String>()
    private val allWhiteList get() = DEFAULT_WHITE_LIST + remoteWhiteList
    @JvmOverloads
    @JvmStatic
    fun isAccessibilityEnabled(context: Context = appContext): Boolean {
        val global = Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Log.d(TAG, "无障碍总开关 accessibility_enabled=$global")
        if (global != 1) return false

        val ids = getEnabledServiceIds(context)
        if (isAnyInWhiteList(ids, allWhiteList)) {
            Log.d(TAG, "命中来源于 AccessibilityManager")
            return true
        }
        val secureIds = getEnabledServiceIdsFromSecure(context)
        return isAnyInWhiteList(secureIds, allWhiteList).also {
            if (it) Log.d(TAG, "命中来源于 Settings.Secure")
        }
    }

    private fun getEnabledServiceIds(context: Context): List<String> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.id }
    }

    private fun getEnabledServiceIdsFromSecure(context: Context): List<String> {
        val raw = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return emptyList()
        return raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isAnyInWhiteList(ids: List<String>, whiteList: List<String>): Boolean {
        Log.d(TAG, "白名单列表 = $whiteList")
        Log.d(TAG, "当前启用服务 = $ids")
        var hit = false
        for (id in ids) {
            for (name in whiteList) {
                if (name.isNotEmpty() && id.contains(name)) {
                    Log.w(TAG, "命中! id=$id  ←  白名单特征=$name")
                    hit = true
                }
            }
        }
        return hit
    }
}