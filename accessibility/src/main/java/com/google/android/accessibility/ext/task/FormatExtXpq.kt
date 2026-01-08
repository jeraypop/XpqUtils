package com.google.android.accessibility.ext.task

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*fun Long.formatTime(): String {
    val m = (this / 1000 / 60 % 60).toInt()
    val s = (this / 1000 % 60).toInt()
    val minutes = if (m < 10) "$m" else m.toString()
    val seconds = if (s < 10) "$s" else s.toString()
    return "${minutes}分${seconds}秒"
}*/

fun Long.formatTime(): String {
    val m = (this / 1000 / 60 % 60).toInt()
    val s = (this / 1000 % 60).toInt()
    val ms = (this % 1000).toInt()  // 计算毫秒部分
    val minutes = if (m < 10) "$m" else m.toString()
    val seconds = if (s < 10) "$s" else s.toString()
    val milliseconds = if (ms < 100) "${ms}" else ms.toString()
    return "${minutes}分${seconds}秒${milliseconds}毫秒"
}


fun Long.getNowString(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.getDefault())
    return sdf.format(Date(this))
}

//========


