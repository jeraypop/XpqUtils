package com.google.android.accessibility.ext.donate

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.accessibility.ext.utils.AliveUtils.toast

/**
 * Created by qiang on 2025/8/9.
 */
class WeXin {
    companion object {
        @JvmStatic
        fun isActivityAvailable(cxt: Context, intent: Intent): Boolean {
            val pm = cxt.packageManager
            if (pm == null) {
                return false
            }
            val list = pm.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )
            return list != null && list.size > 0
        }

        @JvmStatic
        fun startWeZhi(c: Context) {
            val intent = Intent()
            //打开微信，不传参数时，只是打开了微信
            intent.component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
            //这里传true时，只能跳转到扫一扫    方式一   两种方式选用任意一个就行
            intent.putExtra("LauncherUI.From.Scaner.Shortcut", true)

            //当 LauncherUI.From.Scaner.Shortcut 参数不传或者为false，下列参数有效
            //扫一扫          方式二  两种方式选用任意一个就行
            intent.putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_scan_qrcode")
            //收付款
//            intent.putExtra("LauncherUI.Shortcut.LaunchType","launch_type_offline_wallet")
            //个人二维码
//            intent.putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_my_qrcode")
            //联系人
//            intent.putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_voip")


            intent.action = "android.intent.action.VIEW"
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK


            if (isActivityAvailable(c, intent)) {
                c.startActivity(intent)
            } else {
                toast(msg = "微信是否已安装?")
            }
        }
    }
}