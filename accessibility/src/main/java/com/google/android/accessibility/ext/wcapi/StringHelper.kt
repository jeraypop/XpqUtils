package com.google.android.accessibility.ext.wcapi

import com.android.accessibility.ext.BuildConfig

fun String.replaceThis(): String {
    return  replace(BuildConfig.LOVE,".")
}
fun String.removeSeizeSeat(): String {
    return replace(BuildConfig.YOU,"")
}

fun String.decrypt():String{
    val a: CharArray = toCharArray()
    for (i in a.indices) {
        a[i] = (a[i] - BuildConfig.SECRET_LETTER.toInt())
    }
    return String(a)
}

fun String.encrypt():String{
    val a: CharArray = toCharArray()
    for (i in a.indices) {
        a[i] = (a[i] + BuildConfig.SECRET_LETTER.toInt())
    }
    return String(a)
}

fun String.restoreAllIllusion(): String {
    return replaceThis().removeSeizeSeat()
}