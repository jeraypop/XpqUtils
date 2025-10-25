package com.google.android.accessibility.ext.task

fun Long.formatTime(): String {
    val m = (this / 1000 / 60 % 60).toInt()
    val s = (this / 1000 % 60).toInt()
    val minutes = if (m < 10) "$m" else m.toString()
    val seconds = if (s < 10) "$s" else s.toString()
    return "${minutes}分${seconds}秒"
}

//========


