package com.google.android.accessibility.ext.vdisplay

import android.content.Context
import android.widget.LinearLayout
import android.widget.Switch
import androidx.appcompat.app.AlertDialog

/**
 * 虚拟屏设置：总开关 + 锁屏时是否解锁主屏。
 *
 * - 启用虚拟屏：任务是否在副屏执行；
 * - 解锁主屏：开启时锁屏任务先亮屏/解锁主屏（之后走剪贴板粘贴）；
 *   关闭时保持锁屏执行（锁屏下的文本输入走节点 ACTION_SET_TEXT）。
 */
object VDisplaySettings {
    private const val PREFS = "xpq_vdisplay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_UNLOCK = "unlock"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun shouldUnlock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_UNLOCK, false)

    fun showDialog(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dp = context.resources.displayMetrics.density
        val padding = (20 * dp).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val enabledSwitch = Switch(context).apply {
            text = "启用虚拟屏"
            isChecked = prefs.getBoolean(KEY_ENABLED, true)
        }
        val unlockSwitch = Switch(context).apply {
            text = "虚拟屏模式解锁主屏（解锁后走剪贴板粘贴，不解锁则走节点输入）"
            isChecked = prefs.getBoolean(KEY_UNLOCK, false)
            isEnabled = enabledSwitch.isChecked
        }
        // 切换即时写入，不依赖“确定”
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_ENABLED, checked).apply()
            unlockSwitch.isEnabled = checked
        }
        unlockSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_UNLOCK, checked).apply()
        }
        root.addView(enabledSwitch)
        root.addView(unlockSwitch)

        AlertDialog.Builder(context)
            .setTitle("虚拟屏设置")
            .setView(root)
            .setPositiveButton("确定", null)
            .show()
    }
}
