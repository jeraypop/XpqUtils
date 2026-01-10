package com.google.android.accessibility.ext.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.copyToClipboard

/**
 * Company    : 
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/8/22  19:12
 * Description:This is ActivityUtils
 */
object ActivityUtils {

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

    @SuppressLint("UnsafeOptInUsageError")
    @JvmStatic
    fun showVideoDialog(activity: Activity,videoUrl: String) {
        // 创建 Dialog 视图
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_video_player, null)
        val playerView = dialogView.findViewById<PlayerView>(R.id.playerView)

        // 创建播放器
        val player = ExoPlayer.Builder(activity).build()
        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
        player.prepare()
        player.playWhenReady = true

        //playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

        // 构建对话框
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.show()

        // 关闭时释放资源
        dialog.setOnDismissListener {
            player.release()
        }
    }
    /**
     * 获取特定包名应用的版本名称
     * */
    @JvmOverloads
    @JvmStatic
    fun getAppVersionName(context: Context = appContext, packageName: String = MMKVConst.XPQ_WX_PKG): String? {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            info.versionName  // 例如 "8.9.5"
        } catch (e: PackageManager.NameNotFoundException) {
            null  // 应用未安装
        }
    }
    /**
     * 获取特定包名应用的版本号
     * */
    @JvmOverloads
    @JvmStatic
    fun getAppVersionCode(context: Context = appContext, packageName: String = MMKVConst.XPQ_WX_PKG): Long {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            var versionCode = -1L

            // ① 优先尝试旧字段 versionCode
            try {
                @Suppress("DEPRECATION")
                versionCode = info.versionCode.toLong()
            } catch (_: Throwable) {
                // 某些 ROM 可能已删除旧字段
            }

            // ② 如果没取到，再尝试新版 API
            if (versionCode <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    versionCode = info.longVersionCode
                } catch (_: Throwable) {
                }
            }

            versionCode
        } catch (_: PackageManager.NameNotFoundException) {
            -1L
        }
    }


    @JvmStatic
    fun showKaWangDialog(activity: Context,xiaopu: String,weifk: String) {
        fun tiaoZhuan(url: String) {
            copyToClipboard(url)
            AliveUtils.toast(msg = "网址已复制,可手动在浏览器打开")
            try {
                val intent = Intent().apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(url)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                AliveUtils.toast(msg = "无法打开链接")
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("会员(卡密)购买入口")
            .setMessage(
                "以下2个入口(某一个不行就试试另一个)均可购买卡密(查询订单)\n" +
                        "如果点击入口后,无法自动跳转打开浏览器,有2种方法解决:\n" +
                        "❶.如果无法自动跳转到浏览器,请先找到【应用信息->权限管理->链式启动管理->允许本软件启动其它应用】\n" +
                        "❷.可手动打开浏览器,然后粘贴网址(点击下面入口会自动复制)并打开")

            // 链动小铺发卡
            .setPositiveButton("入口1小铺发卡(首选)"){ _, _ ->
                tiaoZhuan(xiaopu)
            }
            // 微发卡
            .setNegativeButton("入口2微发卡网(备用)") { _, _ ->
                tiaoZhuan(weifk)
            }
            //待定
            //.setNeutralButton("入口3 卡网(备用)") { _, _ ->
            //    tiaoZhuan(weifk)
            //}

            .show()
    }


}