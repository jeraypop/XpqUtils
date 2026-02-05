package com.google.android.accessibility.ext.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.copyToClipboard
import com.google.android.accessibility.ext.utils.NumberPickerDialog.dp

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
    @JvmOverloads
    fun showWebViewDialog(activity: Context,url: String) {
        if (activity is FragmentActivity || activity is AppCompatActivity){
            val webFragment = WebDialogFragment.newInstance(url,true)
            webFragment.show(activity.supportFragmentManager, "web_dialog")
            webFragment.toggleDesktopMode()
        }else{
            // 如果不是，提示错误或使用其他方式
            AliveUtils.toast(msg = "网址已复制,请通过系统浏览器打开")
            copyToClipboard(url)
        }

    }



    @JvmStatic
    @JvmOverloads
    fun showKaWangDialog(activity: Context,xiaopu: String,weifk: String,isDan: Boolean = true) {
        fun tiaoZhuan(url: String) {
            copyToClipboard(url)
            AliveUtils.toast(msg = "网址已复制,可手动在浏览器打开")
            if (KeyguardUnLock.getSystemWeb()){
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
            }else{
                //
                if (activity is FragmentActivity || activity is AppCompatActivity){
                    val webFragment = WebDialogFragment.newInstance(url)
                    webFragment.show(activity.supportFragmentManager, "web_dialog")
                    webFragment.toggleDesktopMode()
                }else{
                    // 如果不是，提示错误或使用其他方式
                    AliveUtils.toast(msg = "请通过系统浏览器打开")
                }

            }

        }
        val tip = if (isDan){""}else{"\n类似于用充值卡给手机号码充话费的模式:\n首先在本软件主界面点击左上角齿轮图标,注册一个账号(注册账号免费,未充值的账号可永久使用基础版免费功能),然后通过下面的两个入口去购买充值卡给账号充值会员可用时间\n"}

        val s1 = "PS:请确保基础版免费功能在你手机上可用,再开通会员,如果免费功能在你手机上用不了(原因:可能是你设置不对,也可能是你用的本软件版本太旧),就不要购买会员了\n" + tip

        val s2 = "以下2个入口(某一个不行就试试另一个)均可购买会员(卡密)(查询订单)"

        val s3 = "\n如果点击入口后,无法自动跳转打开浏览器,有2种方法解决:\n" +
                "❶.如果无法自动跳转到浏览器,请先找到【应用信息->权限管理->链式启动管理->允许本软件启动其它应用】\n" +
                "❷.可手动打开浏览器,然后粘贴网址(点击下面入口会自动复制)并打开"

        val root = ScrollView(activity)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val messageView1 = TextView(activity).apply {
            text = s1
            textSize = 14f
            setTextColor(Color.RED)
        }
        val messageView2 = TextView(activity).apply {
            text = s2
            textSize = 14f
            setTextColor(Color.BLUE)
        }
        val messageView3 = TextView(activity).apply {
            text = s3
            textSize = 14f
        }
        val systemWeb = KeyguardUnLock.getSystemWeb()
        val switch = SwitchCompat(activity).apply {
            text = "通过系统浏览器打开"
            textSize = 14f
            showText = true
            textOn = "是"
            textOff = "否"
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            //某些设备在 isChecked = xxx 时可能触发监听 初始化状态（防止误触发）
            setOnCheckedChangeListener(null)
            // 读取已保存状态
            isChecked = systemWeb

            // ★ 关键：切换即保存
            setOnCheckedChangeListener { _, isChecked ->
                messageView3.visibility = if (isChecked) View.VISIBLE else View.GONE
                KeyguardUnLock.setSystemWeb(isChecked)
            }
        }

        messageView3.visibility =
            if (systemWeb) View.VISIBLE else View.GONE

        content.addView(messageView1)
        content.addView(messageView2)
        content.addView(messageView3)
        content.addView(switch)
        root.addView(content)

        AlertDialog.Builder(activity)
            .setTitle("会员(卡密)购买入口")
            .setView(root)
            .setPositiveButton("入口1小铺发卡") { _, _ ->
                if (switch.isChecked) {
                    // 保存状态
                }
                tiaoZhuan(xiaopu)
            }
            .setNegativeButton("入口2微发卡网") { _, _ ->
                tiaoZhuan(weifk)
            }
            //待定
            //.setNeutralButton("入口3 卡网(备用)") { _, _ ->
            //    tiaoZhuan(weifk)
            //}
            .show()





    }


}