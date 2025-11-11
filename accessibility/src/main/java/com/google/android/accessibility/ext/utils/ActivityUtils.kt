package com.google.android.accessibility.ext.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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



}