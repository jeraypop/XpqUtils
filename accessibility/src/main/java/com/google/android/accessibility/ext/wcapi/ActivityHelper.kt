package com.google.android.accessibility.ext.wcapi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.view.View
import android.widget.Toast
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.donate.ScreenshotHelper
import com.google.android.accessibility.ext.utils.AliveUtils.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


import java.net.URISyntaxException
internal fun Activity.goAliPayClient(urlCode: String) {
    return try {
        startActivity(goAliPay(urlCode))
    } catch (e: URISyntaxException) {
        toast(msg = getString(R.string.url_code_error))
    } catch (e: ActivityNotFoundException) {
        toast(msg = getString(R.string.alipay_is_not_installed))
    }
}


@SuppressLint("NewApi")
internal fun Activity.startWeZhi(targetView: View) {
    CoroutineScope(Dispatchers.Main).launch {
        // 在后台线程中执行截图操作
        withContext(Dispatchers.IO) {
            val fileName = "screenshot_" + System.currentTimeMillis() + ".png"
            ScreenshotHelper.captureAndSaveToGallery(this@startWeZhi, targetView, fileName)
        }

        // 回到主线程执行UI操作
        openWeChatScan()
        toast(msg = "在相册中选择二维码扫描")
    }
}