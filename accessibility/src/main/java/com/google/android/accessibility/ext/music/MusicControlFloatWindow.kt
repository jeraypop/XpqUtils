package com.google.android.accessibility.ext.music

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowManager
import com.android.accessibility.ext.databinding.FloatMusicControlBinding
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract

/**
 * 音乐控制悬浮窗：外部调用 [MusicPlayer.playSaved] 起播后弹出，提供一键关闭
 * 「歌曲播放 / 震动 / 自定义语音播报」三个开关，以及一个关闭按钮。
 *
 * 窗口类型优先使用无障碍服务类型 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * （由无障碍服务实例持有，无需 SYSTEM_ALERT_WINDOW 权限）；当无障碍服务不可用（instance 为 null）
 * 时回退到 [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]（需要悬浮窗权限）。
 */
object MusicControlFloatWindow {

    private const val TAG = "MusicControlFloatWindow"
    private const val ON_COLOR = 0xFFF6D36B.toInt()   // music_accent 金
    private const val ON_TEXT = 0xFF1A1A1A.toInt()
    private const val OFF_COLOR = 0xFF4A4A4A.toInt()
    private const val OFF_TEXT = 0xFFFFFFFF.toInt()

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var binding: FloatMusicControlBinding? = null
    /** 顶部居中提示文字（外部传入，可空；为空则不显示） */
    private var tip: String? = null

    /** 当前是否正在显示 */
    fun isShowing(): Boolean = floatingView != null

    /**
     * 显示悬浮窗。优先使用无障碍服务类型，无障碍服务不可用则回退应用悬浮窗。
     * @param accessibilityService 无障碍服务实例；不传则自动取 [SelectToSpeakServiceAbstract.instance]
     * @param tip 顶部居中显示的提示文字；为空或 null 则不显示该提示行
     */
    fun show(
        accessibilityService: AccessibilityService? = SelectToSpeakServiceAbstract.instance,
        tip: String? = null
    ) {
        this.tip = tip?.trim()?.takeIf { it.isNotEmpty() }
        if (floatingView != null) {
            refresh()
            return
        }
        val ctx: Context = accessibilityService ?: appContext

        val windowType = resolveWindowType(ctx, accessibilityService)
        if (windowType == null) return // 无可用窗口类型（如未授予悬浮窗权限），已提示

        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val b = FloatMusicControlBinding.inflate(LayoutInflater.from(ctx))
        binding = b
        floatingView = b.root

        val dm = ctx.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // 先量出自然尺寸，便于初始水平居中
        b.root.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val viewW = b.root.measuredWidth
        val viewH = b.root.measuredHeight

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (viewW > 0) kotlin.math.max(0, (screenW - viewW) / 2) else screenW / 2
            y = (screenH * 0.12f).toInt().coerceAtLeast(0)
        }

        b.btnClose.setOnClickListener { hide() }
        b.btnPlayToggle.setOnClickListener { togglePlay() }
        b.btnVibrateToggle.setOnClickListener { toggleVibrate() }
        b.btnTtsToggle.setOnClickListener { toggleTts() }
        b.root.setOnTouchListener(
            DragTouchListener(windowManager, layoutParams, screenW, screenH)
        )

        // 监听播放状态，保持「歌曲播放」按钮与实际播放同步
        MusicManager.addListener(stateListener)

        refresh()
        try {
            windowManager?.addView(floatingView, layoutParams)
            Log.i(TAG, "悬浮窗显示，type=$windowType")
        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮窗失败: ${e.message}")
            AliveUtils.toast(msg = "音乐控制悬浮窗显示失败：${e.message}")
            cleanup()
        }
    }

    /** 解析窗口类型；返回 null 表示无法显示（已提示用户） */
    private fun resolveWindowType(ctx: Context, svc: AccessibilityService?): Int? {
        return if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 优先：无障碍服务类型（免 SYSTEM_ALERT_WINDOW 权限）
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(ctx)) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                AliveUtils.toast(msg = "请开启悬浮窗权限以显示音乐控制面板")
                requestOverlayPermission(ctx)
                null
            }
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun requestOverlayPermission(ctx: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (_: Exception) { }
    }

    private val stateListener = object : MusicManager.MusicListener {
        override fun onMusicState(state: MusicManager.PlayState, index: Int) = refreshPlay()
        override fun onMusicProgress(positionMs: Long, durationMs: Long) {}
        override fun onMusicPlaylist(list: List<Song>, index: Int) {}
        override fun onMusicError(message: String) {}
    }

    /** 刷新三个开关与提示文字的显示状态 */
    private fun refresh() {
        refreshTip()
        refreshPlay()
        refreshVibrate()
        refreshTts()
    }

    /** 顶部居中提示：有文字则显示，否则隐藏该行 */
    private fun refreshTip() {
        binding?.tvTip?.apply {
            val t = tip
            if (t.isNullOrEmpty()) {
                visibility = View.GONE
            } else {
                text = t
                visibility = View.VISIBLE
            }
        }
    }

    private fun refreshPlay() {
        val playing = MusicManager.getState() == MusicManager.PlayState.PLAYING
        binding?.btnPlayToggle?.apply {
            text = if (playing) "开" else "关"
            setBackgroundColor(if (playing) ON_COLOR else OFF_COLOR)
            setTextColor(if (playing) ON_TEXT else OFF_TEXT)
        }
    }

    private fun refreshVibrate() {
        val on = MusicStore.isVibrateOn()
        binding?.btnVibrateToggle?.apply {
            text = if (on) "开" else "关"
            setBackgroundColor(if (on) ON_COLOR else OFF_COLOR)
            setTextColor(if (on) ON_TEXT else OFF_TEXT)
        }
    }

    private fun refreshTts() {
        val on = MusicStore.isTtsOn()
        binding?.btnTtsToggle?.apply {
            text = if (on) "开" else "关"
            setBackgroundColor(if (on) ON_COLOR else OFF_COLOR)
            setTextColor(if (on) ON_TEXT else OFF_TEXT)
        }
    }

    /** 歌曲播放：开→停止；关且歌单非空→续播/起播 */
    private fun togglePlay() {
        if (MusicManager.getState() == MusicManager.PlayState.PLAYING) {
            MusicManager.stop()
        } else if (MusicManager.getPlaylist().isNotEmpty()) {
            MusicManager.resume()
        }
        refreshPlay()
    }

    /** 震动：切换开关并立即生效 */
    private fun toggleVibrate() {
        val on = !MusicStore.isVibrateOn()
        MusicStore.setVibrateOn(on)
        MusicManager.onVibrateSettingChanged(on)
        refreshVibrate()
    }

    /** 自定义语音播报：切换开关并立即生效 */
    private fun toggleTts() {
        val on = !MusicStore.isTtsOn()
        MusicStore.setTtsOn(on)
        MusicManager.onTtsSettingChanged(on)
        refreshTts()
    }

    /** 关闭并移除悬浮窗 */
    fun hide() {
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) { }
        }
        cleanup()
    }

    private fun cleanup() {
        MusicManager.removeListener(stateListener)
        floatingView = null
        binding = null
        layoutParams = null
        windowManager = null
    }

    /** 简单拖动（限制在屏幕内，无吸边动画） */
    private class DragTouchListener(
        private val wm: WindowManager?,
        private val params: WindowManager.LayoutParams?,
        private val screenW: Int,
        private val screenH: Int
    ) : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            params ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    if (!dragging && (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6)) {
                        dragging = true
                    }
                    if (dragging) {
                        val maxX = (screenW - v.width).coerceAtLeast(0)
                        val maxY = (screenH - v.height).coerceAtLeast(0)
                        params.x = (params.x + dx).toInt().coerceIn(0, maxX)
                        params.y = (params.y + dy).toInt().coerceIn(0, maxY)
                        wm?.updateViewLayout(v, params)
                        lastX = e.rawX
                        lastY = e.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.performClick()
                    return true
                }
            }
            return false
        }
    }
}
