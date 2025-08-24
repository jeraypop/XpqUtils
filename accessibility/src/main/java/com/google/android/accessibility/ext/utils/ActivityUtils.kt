package com.google.android.accessibility.ext.utils

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi

/**
 * Company    : 
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/8/22  19:12
 * Description:This is ActivityUtils
 */
class ActivityUtils {

    fun startActivityForResult(activity: ComponentActivity) {
        val filePickerLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                //=============获取文件的真实路径======================
                // dealData(data)
                //=============获取文件的真实路径======================
            }
        }

        // 使用 launcher 启动文件选择器
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        filePickerLauncher.launch(intent)
    }

}