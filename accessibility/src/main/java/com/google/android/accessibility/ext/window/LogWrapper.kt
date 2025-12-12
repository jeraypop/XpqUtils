package com.google.android.accessibility.ext.window

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.blankj.utilcode.util.TimeUtils
import com.google.android.accessibility.ext.CoroutineWrapper
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

import kotlinx.coroutines.flow.MutableSharedFlow

object LogWrapper {
    var logCache = StringBuilder("")

    val logAppendValue = MutableSharedFlow<Pair<String, String>>()

    fun String.logAppend(): String {
        return logAppend(this)
    }

    /*    fun logAppend(msg: CharSequence): String {
            if (logCache.isNotEmpty()) {
                logCache.append("\n")
            }
            if (logCache.length > 5000) {
                logCache.delete(0, logCache.length - 5000)
            }
            logCache.append(TimeUtils.getNowString())
            logCache.append("\n")
            logCache.append(msg)
            CoroutineWrapper.launch {
                logAppendValue.emit(Pair("\n${TimeUtils.getNowString()}\n$msg", logCache.toString()))
            }
            return msg.toString()
        }*/

    fun logAppend(msg: CharSequence): String {
        if (logCache.isNotEmpty()) {
            logCache.append("\n")
        }

        // 添加新日志前检查行数并清理
        val lines = logCache.split('\n')
        if (lines.size > 1000) {
            val startIndex = lines[1000].let { logCache.indexOf(it) + it.length + 1 }
            logCache.delete(0, startIndex)
        }

        logCache.append(TimeUtils.getNowString())
        logCache.append("\n")
        logCache.append(msg)

        CoroutineWrapper.launch {
            logAppendValue.emit(Pair("\n${TimeUtils.getNowString()}\n$msg", logCache.toString()))
        }

        return msg.toString()
    }


    fun clearLog() {
        logCache = StringBuilder("")
        CoroutineWrapper.launch { logAppendValue.emit(Pair("", "")) }
    }

    fun copyLogToClipboard() {
        try {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Log Content", logCache.toString())
            clipboard.setPrimaryClip(clip)
            AliveUtils.toast(msg = "已复制日志到剪贴板")
        } catch (e: Exception){
            AliveUtils.toast(msg = "复制日志出现错误!"+e.message)
        }

    }

}