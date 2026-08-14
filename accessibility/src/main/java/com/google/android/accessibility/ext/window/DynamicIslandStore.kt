package com.google.android.accessibility.ext.window

import android.content.Context
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * 灵动岛（类 Dynamic Island 顶部悬浮条）的偏好持久化（SharedPreferences）。
 * 与音乐模块的 [com.google.android.accessibility.ext.music.MusicStore] 相互独立。
 */
object DynamicIslandStore {
    private const val PREFS = "xpq_dynamic_island"
    private const val KEY_ENABLED = "island_enabled"
    private const val KEY_WIDTH_DP = "island_width_dp"
    private const val KEY_HEIGHT_DP = "island_height_dp"
    private const val KEY_TEXT_SIZE_SP = "island_text_size_sp"
    private const val KEY_DURATION_SEC = "island_duration_sec"
    private const val KEY_VERTICAL = "island_vertical"
    private const val KEY_VMARGIN_DP = "island_vmargin_dp"
    private const val KEY_HORIZONTAL = "island_horizontal"
    private const val KEY_BG_COLOR = "island_bg_color"
    private const val KEY_TEXT_COLOR = "island_text_color"

    const val MIN_WIDTH_DP = 120
    /** 存储安全上限；设置界面滑块的实际最大值按设备屏幕宽度动态计算 */
    const val MAX_WIDTH_DP = 2000
    const val MIN_HEIGHT_DP = 24
    const val MAX_HEIGHT_DP = 80
    const val MIN_TEXT_SIZE_SP = 9
    const val MAX_TEXT_SIZE_SP = 24
    const val MIN_DURATION_SEC = 1
    const val MAX_DURATION_SEC = 10
    const val MIN_VMARGIN_DP = 0
    const val MAX_VMARGIN_DP = 200
    /** 背景色默认：半透明黑胶囊 #D9000000 */
    const val DEFAULT_BG_COLOR = 0xD9000000.toInt()
    /** 文字颜色默认：白色 */
    const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()

    private const val DEFAULT_WIDTH_DP = 300
    private const val DEFAULT_HEIGHT_DP = 44
    private const val DEFAULT_TEXT_SIZE_SP = 13
    private const val DEFAULT_DURATION_SEC = 3
    private const val DEFAULT_VERTICAL = "top"
    private const val DEFAULT_VMARGIN_DP = 10
    private const val DEFAULT_HORIZONTAL = "center"
    private const val DEFAULT_BG_COLOR_INT = 0xD9000000.toInt()
    private const val DEFAULT_TEXT_COLOR_INT = 0xFFFFFFFF.toInt()

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean =
        try { prefs().getBoolean(KEY_ENABLED, false) } catch (_: Exception) { false }

    fun setEnabled(on: Boolean) {
        try { prefs().edit().putBoolean(KEY_ENABLED, on).apply() } catch (_: Exception) { }
    }

    fun getWidthDp(): Int =
        try { prefs().getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP).coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP) }
        catch (_: Exception) { DEFAULT_WIDTH_DP }

    fun setWidthDp(dp: Int) {
        try { prefs().edit().putInt(KEY_WIDTH_DP, dp.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)).apply() }
        catch (_: Exception) { }
    }

    fun getHeightDp(): Int =
        try { prefs().getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP).coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP) }
        catch (_: Exception) { DEFAULT_HEIGHT_DP }

    fun setHeightDp(dp: Int) {
        try { prefs().edit().putInt(KEY_HEIGHT_DP, dp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)).apply() }
        catch (_: Exception) { }
    }

    fun getTextSizeSp(): Int =
        try { prefs().getInt(KEY_TEXT_SIZE_SP, DEFAULT_TEXT_SIZE_SP).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP) }
        catch (_: Exception) { DEFAULT_TEXT_SIZE_SP }

    fun setTextSizeSp(sp: Int) {
        try { prefs().edit().putInt(KEY_TEXT_SIZE_SP, sp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)).apply() }
        catch (_: Exception) { }
    }

    /** 新消息停留多久自动消失（秒） */
    fun getDurationSec(): Int =
        try { prefs().getInt(KEY_DURATION_SEC, DEFAULT_DURATION_SEC).coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC) }
        catch (_: Exception) { DEFAULT_DURATION_SEC }

    fun setDurationSec(sec: Int) {
        try { prefs().edit().putInt(KEY_DURATION_SEC, sec.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)).apply() }
        catch (_: Exception) { }
    }

    /** 垂直位置："top" 贴顶部，"bottom" 贴底部 */
    fun getVertical(): String =
        try {
            val v = prefs().getString(KEY_VERTICAL, DEFAULT_VERTICAL) ?: DEFAULT_VERTICAL
            if (v == "bottom") "bottom" else "top"
        } catch (_: Exception) { DEFAULT_VERTICAL }

    fun setVertical(v: String) {
        try { prefs().edit().putString(KEY_VERTICAL, if (v == "bottom") "bottom" else "top").apply() }
        catch (_: Exception) { }
    }

    /** 距边缘距离（dp），用于垂直位置 */
    fun getVMarginDp(): Int =
        try { prefs().getInt(KEY_VMARGIN_DP, DEFAULT_VMARGIN_DP).coerceIn(MIN_VMARGIN_DP, MAX_VMARGIN_DP) }
        catch (_: Exception) { DEFAULT_VMARGIN_DP }

    fun setVMarginDp(dp: Int) {
        try { prefs().edit().putInt(KEY_VMARGIN_DP, dp.coerceIn(MIN_VMARGIN_DP, MAX_VMARGIN_DP)).apply() }
        catch (_: Exception) { }
    }

    /** 水平位置："left"、"center"、"right" */
    fun getHorizontal(): String =
        try {
            when (prefs().getString(KEY_HORIZONTAL, DEFAULT_HORIZONTAL) ?: DEFAULT_HORIZONTAL) {
                "left" -> "left"
                "right" -> "right"
                else -> "center"
            }
        } catch (_: Exception) { DEFAULT_HORIZONTAL }

    fun setHorizontal(h: String) {
        try {
            val v = when (h) {
                "left" -> "left"
                "right" -> "right"
                else -> "center"
            }
            prefs().edit().putString(KEY_HORIZONTAL, v).apply()
        } catch (_: Exception) { }
    }

    /** 悬浮窗背景色（ARGB int） */
    fun getBgColor(): Int =
        try { prefs().getInt(KEY_BG_COLOR, DEFAULT_BG_COLOR_INT) } catch (_: Exception) { DEFAULT_BG_COLOR_INT }

    fun setBgColor(color: Int) {
        try { prefs().edit().putInt(KEY_BG_COLOR, color).apply() } catch (_: Exception) { }
    }

    /** 悬浮窗文字颜色（ARGB int） */
    fun getTextColor(): Int =
        try { prefs().getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR_INT) } catch (_: Exception) { DEFAULT_TEXT_COLOR_INT }

    fun setTextColor(color: Int) {
        try { prefs().edit().putInt(KEY_TEXT_COLOR, color).apply() } catch (_: Exception) { }
    }

    /** 恢复默认：除「启用开关」外，重置全部外观/位置/尺寸设置为默认值 */
    fun resetToDefaults() {
        try {
            prefs().edit().apply {
                putInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP)
                putInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP)
                putInt(KEY_TEXT_SIZE_SP, DEFAULT_TEXT_SIZE_SP)
                putInt(KEY_DURATION_SEC, DEFAULT_DURATION_SEC)
                putString(KEY_VERTICAL, DEFAULT_VERTICAL)
                putInt(KEY_VMARGIN_DP, DEFAULT_VMARGIN_DP)
                putString(KEY_HORIZONTAL, DEFAULT_HORIZONTAL)
                putInt(KEY_BG_COLOR, DEFAULT_BG_COLOR_INT)
                putInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR_INT)
            }.apply()
        } catch (_: Exception) { }
    }
}
