package com.google.android.accessibility.baoshi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.util.Calendar

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/3/11  10:34
 * Description:This is TimeReportScheduler
 */
object TimeReportScheduler {

    private const val REQ_EARLY = 2001
    private const val REQ_EXACT = 2002
    //设置闹钟
    @JvmOverloads
    @JvmStatic
    fun schedule(context: Context= appContext) {

        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val target = nextHalfHour()

        val earlyIntent = Intent(context, TimeReportReceiver::class.java)
        earlyIntent.putExtra("type", "early")

        val exactIntent = Intent(context, TimeReportReceiver::class.java)
        exactIntent.putExtra("type", "exact")

        val earlyPI = PendingIntent.getBroadcast(
            context,
            REQ_EARLY,
            earlyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exactPI = PendingIntent.getBroadcast(
            context,
            REQ_EXACT,
            exactIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 提前30秒
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            target - 30_000,
            earlyPI
        )

        // 整点兜底
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            target,
            exactPI
        )
    }
    //计算时间
    @JvmOverloads
    @JvmStatic
    fun nextHalfHour(): Long {

        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.MINUTE)

        if (minute < 30) {
            cal.set(Calendar.MINUTE, 30)
        } else {
            cal.add(Calendar.HOUR_OF_DAY, 1)
            cal.set(Calendar.MINUTE, 0)
        }

        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        return cal.timeInMillis
    }
   //时间容差判断
   @JvmOverloads
   @JvmStatic
    fun isTimeMatchWithTolerance(
        targetHour: Int,
        targetMin: Int,
        toleranceMinutes: Int = 2
    ): Boolean {

        val now = Calendar.getInstance()

        val current =
            now.get(Calendar.HOUR_OF_DAY) * 60 +
                    now.get(Calendar.MINUTE)

        val target =
            targetHour * 60 + targetMin

        var diff = Math.abs(current - target)

        diff = minOf(diff, 1440 - diff)

        return diff <= toleranceMinutes
    }
    //唤醒CPU
    @JvmOverloads
    @JvmStatic
    fun wakeCPU(context: Context = appContext) {

        val pm =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:TimeReport"
        )

        wl.acquire(20_000)

        Handler(Looper.getMainLooper()).postDelayed({

            if (wl.isHeld) wl.release()

        }, 20_000)
    }

    private const val PREF_NAME = "time_report_prefs"
    private const val KEY_ENABLED = "report_enabled"
    @JvmOverloads
    @JvmStatic
    fun isEnabled(context: Context = appContext): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_ENABLED, false) // 默认关闭
    }
    @JvmOverloads
    @JvmStatic
    fun setEnabled(enabled: Boolean, context: Context = appContext) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

}