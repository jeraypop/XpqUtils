package com.google.android.accessibility.ext.utils

import androidx.core.content.FileProvider

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/12/14  21:43
 * Description:This is MyCustomFileProvider
 */
class MyCustomFileProvider : FileProvider() {
    // 可以添加自定义逻辑
    override fun onCreate(): Boolean {
        // 自定义初始化逻辑
        return super.onCreate()
    }
}
