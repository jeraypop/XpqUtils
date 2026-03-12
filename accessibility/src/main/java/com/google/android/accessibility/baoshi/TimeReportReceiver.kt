package com.google.android.accessibility.baoshi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.util.Calendar

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/3/11  10:35
 * Description:This is TimeReportReceiver
 * 执行 报时逻辑
 */
class TimeReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 无论开关状态，先唤醒 CPU，保证闹钟继续调度
        TimeReportScheduler.wakeCPU(context)
        // 如果开关关闭，直接跳过报时逻辑
        if (!TimeReportScheduler.isEnabled(context)) {
            // 重新调度下一次
            TimeReportScheduler.schedule(context)
            return
        }

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        if (!TimeReportScheduler.isTimeMatchWithTolerance(hour, minute, 2)) {
            TimeReportScheduler.schedule(context)
            return
        }

        speak(context, hour, minute)

        TimeReportScheduler.schedule(context)
    }
    @JvmOverloads
    fun speak(context: Context = appContext, hour: Int, minute: Int) {

        val text = if (minute == 0) {
            "现在是 $hour 点整"
        } else {
            "现在是 $hour 点 $minute 分"
        }

        TTSManager.speak(context, text)
    }
}