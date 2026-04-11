package com.google.android.accessibility.ext.utils

import android.content.Context
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.util.concurrent.TimeUnit

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/4/11  12:51
 * Description:This is InstallGateUtils
 */
object InstallGateUtils {
    enum class InstallGateType(val durationMillis: Long) {

        // 小时级
        ONE_HOUR(TimeUnit.HOURS.toMillis(1)),
        SIX_HOURS(TimeUnit.HOURS.toMillis(6)),
        TWELVE_HOURS(TimeUnit.HOURS.toMillis(12)), // 半天

        // 天级
        ONE_DAY(TimeUnit.DAYS.toMillis(1)),
        THREE_DAYS(TimeUnit.DAYS.toMillis(3)),
        SEVEN_DAYS(TimeUnit.DAYS.toMillis(7));

    }

    /*
    * 2小时
    * InstallGate.isAllowed(durationMillis = TimeUnit.HOURS.toMillis(2))
    *
    *45分钟
    * InstallGate.isAllowed(durationMillis = TimeUnit.MINUTES.toMillis(45))
    *
    * */
    @JvmStatic
    @JvmOverloads
    fun isAllowed(context: Context = appContext,
                  type: InstallGateType = InstallGateType.ONE_DAY,
                  durationMillis: Long = 0L): Boolean {
        val installTime = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .firstInstallTime
        } catch (e: Exception) {
            return false
        }

        val now = System.currentTimeMillis()
        if (now < installTime) return false

        val def = now - installTime
        val defMillis = if (durationMillis > 0){
            durationMillis
        }else{
            type.durationMillis
        }



        return def >= defMillis
    }
}