package com.google.android.accessibility.ext.utils

import android.content.Context
import android.provider.Settings
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * Company    : 
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/6/27  3:07
 * Description:This is XPQAccUtils
 */
object XPQAccUtils {

    /**
     * 开启指定无障碍服务（保留已开启的其它服务）
     *
     * @param context Context
     * @param services 服务名，格式：packageName/className
     *
     *  "${context.packageName}/${AccessibilityServiceImp::class.java.name}",
     *     "${context.packageName}/${SelectToSpeakService::class.java.name}"
     */
    @JvmOverloads
    @JvmStatic
    fun enableAccessibilityServices(
        context: Context = appContext ,
        vararg services: String
    ): Boolean {

        if (services.isEmpty()) {
            return false
        }

        val resolver = context.contentResolver

        val enabledServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
            ?.split(":")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toMutableSet()
            ?: mutableSetOf()

        enabledServices.addAll(services)

        val success1 = Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            enabledServices.joinToString(":")
        )

        val success2 = Settings.Secure.putInt(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            1
        )

        return success1 && success2
    }

    /**
     * 关闭指定无障碍服务（保留其它服务）
     *
     * @param context Context
     * @param services 服务名，格式：packageName/className
     */
    @JvmOverloads
    @JvmStatic
    fun disableAccessibilityServices(
        context: Context = appContext,
        vararg services: String
    ): Boolean {

        if (services.isEmpty()) {
            return false
        }

        val resolver = context.contentResolver

        val enabledServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
            ?.split(":")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toMutableSet()
            ?: return true

        enabledServices.removeAll(services.toSet())

        val success = Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            enabledServices.joinToString(":")
        )

        if (enabledServices.isEmpty()) {
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
        }

        return success
    }
}