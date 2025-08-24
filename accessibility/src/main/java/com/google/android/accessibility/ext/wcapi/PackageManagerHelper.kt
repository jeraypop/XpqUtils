package com.google.android.accessibility.ext.wcapi

import android.content.Intent
import android.content.pm.PackageManager

//检查系统中是否存在能够处理指定Intent的Activity
internal fun PackageManager?.isExist(intent: Intent): Boolean {
    this ?: return false
    return queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
}