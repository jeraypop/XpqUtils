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
    private const val KEY_PREVIEW = "preview"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun shouldUnlock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_UNLOCK, false)

    fun isPreviewEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREVIEW, true)

    private fun unlockText(unlocked: Boolean): String =
        if (unlocked) "解锁主屏：需要解锁设备后才能执行任务"
        else "不解锁主屏：不需要解锁设备就能执行任务，但被检测到几率大一点"

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
            text = unlockText(prefs.getBoolean(KEY_UNLOCK, false))
            isChecked = prefs.getBoolean(KEY_UNLOCK, false)
            isEnabled = enabledSwitch.isChecked
        }
        val previewSwitch = Switch(context).apply {
            text = "显示虚拟屏预览"
            isChecked = prefs.getBoolean(KEY_PREVIEW, true)
        }
        // 切换即时写入，不依赖“确定”
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_ENABLED, checked).apply()
            unlockSwitch.isEnabled = checked
        }
        unlockSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_UNLOCK, checked).apply()
            unlockSwitch.text = unlockText(checked)
        }
        previewSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_PREVIEW, checked).apply()
        }
        root.addView(enabledSwitch)
        root.addView(unlockSwitch)
        root.addView(previewSwitch)

        AlertDialog.Builder(context)
            .setTitle("虚拟屏设置")
            .setView(root)
            .setPositiveButton("确定", null)
            .show()
    }
}
