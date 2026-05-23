package com.google.android.accessibility.ext.utils

import android.content.Context
import android.os.Build
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/5/23  15:32
 * Description:This is AppVersionUtil
 */
/**
 * App 版本信息工具类
 * 适用于库模块，可动态获取宿主应用 versionName 和 versionCode
 */
object AppInfoUtil {

    const val UNKNOWN = "unknown"

    /**
     * 获取指定包名的应用名称
     *
     * @param context 上下文（用于获取 PackageManager）
     * @param pkgName 目标应用包名
     * @return 应用名称，失败返回 UNKNOWN
     */
    @JvmStatic
    @JvmOverloads
    fun getAppName(context: Context = appContext, pkgName: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            UNKNOWN
        }
    }

    /**
     * 获取指定包名的 versionName
     *
     * @param context 上下文
     * @param pkgName 目标应用包名
     * @return versionName，失败返回 UNKNOWN
     */
    @JvmStatic
    @JvmOverloads
    fun getVersionName(context: Context = appContext, pkgName: String): String {
        return try {
            val info = context.packageManager.getPackageInfo(pkgName, 0)
            info.versionName ?: UNKNOWN
        } catch (e: Exception) {
            UNKNOWN
        }
    }

    /**
     * 获取指定包名的 versionCode（兼容 API 28+）
     *
     * @param context 上下文
     * @param pkgName 目标应用包名
     * @return versionCode，失败返回 0L
     */
    @JvmStatic
    @JvmOverloads
    fun getVersionCode(context: Context = appContext, pkgName: String): Long {
        return try {
            val info = context.packageManager.getPackageInfo(pkgName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取指定应用完整信息
     *
     * @param context 上下文
     * @param pkgName 目标应用包名
     * @return XPQ_AppInfo（包含应用名 + versionName + versionCode）
     */
    @JvmStatic
    @JvmOverloads
    fun getAppInfo(context: Context = appContext, pkgName: String): XPQ_AppInfo {
        return XPQ_AppInfo(
            appName = getAppName(context, pkgName),
            versionName = getVersionName(context, pkgName),
            versionCode = getVersionCode(context, pkgName)
        )
    }

    /**
     * 获取当前宿主应用信息
     *
     * @param context 上下文
     * @return 当前应用的 XPQ_AppInfo
     */
    @JvmStatic
    @JvmOverloads
    fun getSelfAppInfo(context: Context = appContext): XPQ_AppInfo {
        return getAppInfo(context, context.packageName)
    }
}

/**
 * 应用信息数据结构
 */
data class XPQ_AppInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Long
)