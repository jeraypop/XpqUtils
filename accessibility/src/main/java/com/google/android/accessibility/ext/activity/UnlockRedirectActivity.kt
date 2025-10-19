package com.google.android.accessibility.ext.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.accessibility.ext.R

class UnlockRedirectActivity : AppCompatActivity() {
    // 给系统一点时间执行任务切换，避免视觉抖动
    private val finishDelayMs = 120L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不要 setContentView（保持透明）
        // 直接启动桌面（HOME）
        goToHome()

        // 稍作延迟再 finish，以确保桌面已经被系统激活
        Handler(Looper.getMainLooper()).postDelayed({
            // 使用 finishAndRemoveTask 尝试移除本任务（更干净）
            try {
                finishAndRemoveTask()
            } catch (e: Exception) {
                // 兼容处理：某些 ROM/版本上 finishAndRemoveTask 可能异常
                finish()
            }
        }, finishDelayMs)
    }

    private fun goToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        // 使用 startActivity 启动系统桌面
        startActivity(homeIntent)
    }

    // 保险：如果被用户或系统直接销毁，也确保 no-op
    override fun onDestroy() {
        super.onDestroy()
    }


    companion object {
        /**
         * 推荐的启动方式（从锁屏 Activity 调用）。把 flags 全写好，避免把当前 task 留在最近任务或触发恢复。
         */
        fun startFromLockScreen(activity: Context) {
            val intent = Intent(activity, UnlockRedirectActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            activity.startActivity(intent)
        }
    }


}