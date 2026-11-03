package com.google.android.accessibility.ext.vdisplay

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 虚拟屏设置：总开关 + 锁屏时是否解锁主屏 + 是否显示预览。
 *
 * - 启用虚拟屏：任务是否在副屏执行；
 * - 解锁主屏：开启时锁屏任务先亮屏/解锁主屏（之后走剪贴板粘贴）；
 *   关闭时保持锁屏执行（锁屏下的文本输入走节点 ACTION_SET_TEXT）；
 * - 显示虚拟屏预览：任务运行时右上角是否显示副屏实时画面。
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

    private fun enabledText(enabled: Boolean): String =
        if (enabled) "开启后任务在副屏执行，不影响主屏操作"
        else "任务在主屏执行，会占用主屏操作"

    private fun unlockText(unlocked: Boolean): String =
        if (unlocked) "需要解锁设备后才能执行任务，推荐开启"
        else "不需要解锁设备就能执行任务，但被检测到几率大一点"

    private fun previewText(preview: Boolean): String =
        if (preview) "任务运行时在右上角显示副屏实时画面"
        else "任务运行时不在主屏显示副屏画面"

    private fun subtitle(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFF666666.toInt())
        setPadding(0, (2 * context.resources.displayMetrics.density).toInt(), 0, 0)
    }

    /** 一行设置：左侧「主标题 + 副标题」，右侧开关。 */
    private fun row(context: Context, title: String, subtitleView: TextView, switch: Switch): LinearLayout {
        val dp = context.resources.displayMetrics.density
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF000000.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        left.addView(subtitleView)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            addView(left)
            addView(switch)
        }
    }

    fun showDialog(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dp = context.resources.displayMetrics.density
        val padding = (20 * dp).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(context).apply {
            text = "需要 Android 11 及以上，且工作在Shizuku模式。" +
                "任务在副屏运行，主屏可正常使用(比如刷视频，玩游戏等)。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF888888.toInt())
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        val enabledSwitch = Switch(context).apply { isChecked = prefs.getBoolean(KEY_ENABLED, true) }
        val unlockSwitch = Switch(context).apply {
            isChecked = prefs.getBoolean(KEY_UNLOCK, false)
            isEnabled = enabledSwitch.isChecked
        }
        val previewSwitch = Switch(context).apply {
            isChecked = prefs.getBoolean(KEY_PREVIEW, true)
            isEnabled = enabledSwitch.isChecked
        }
        val unlockSubtitle = subtitle(context, unlockText(prefs.getBoolean(KEY_UNLOCK, false)))
        val enabledSubtitle = subtitle(context, enabledText(prefs.getBoolean(KEY_ENABLED, true)))
        val previewSubtitle = subtitle(context, previewText(prefs.getBoolean(KEY_PREVIEW, true)))

        // 切换即时写入，不依赖“确定”
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_ENABLED, checked).apply()
            unlockSwitch.isEnabled = checked
            previewSwitch.isEnabled = checked
            enabledSubtitle.text = enabledText(checked)
        }
        unlockSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_UNLOCK, checked).apply()
            unlockSubtitle.text = unlockText(checked)
        }
        previewSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_PREVIEW, checked).apply()
            previewSubtitle.text = previewText(checked)
        }

        root.addView(row(context, "启用虚拟屏", enabledSubtitle, enabledSwitch))
        root.addView(row(context, "解锁主屏", unlockSubtitle, unlockSwitch))
        root.addView(row(context, "显示虚拟屏预览", previewSubtitle, previewSwitch))

        AlertDialog.Builder(context)
            .setTitle("虚拟屏设置")
            .setView(root)
            .setPositiveButton("确定", null)
            .show()
    }
}
