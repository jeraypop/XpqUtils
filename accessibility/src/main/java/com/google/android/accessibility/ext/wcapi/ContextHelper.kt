package com.google.android.accessibility.ext.wcapi

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.accessibility.ext.utils.AliveUtils.toast


internal fun Context.go(intent: Intent, msg: String) {
    if (packageManager.isExist(intent)) {
        intent.action = Intent.ACTION_VIEW
        //Intent.FLAG_ACTIVITY_CLEAR_TOP  确保打开的是首页
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    } else {
        toast(msg = msg)

    }
}

internal fun Context.goAndCash(intent: Intent, msg: String) {
    try {
        intent.action = Intent.ACTION_VIEW
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    } catch (e: Exception) {
        toast(msg = msg)
    }
}

