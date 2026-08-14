package com.google.android.accessibility.ext.window

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.SeekBar
import android.app.AlertDialog
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.DialogColorPickerBinding
import com.android.accessibility.ext.databinding.DialogDynamicIslandSettingsBinding
import com.android.accessibility.ext.databinding.DynamicIslandFloatBinding
import com.google.android.accessibility.ext.CoroutineWrapper
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.window.LogWrapper
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * 类「灵动岛」顶部悬浮条：订阅 [LogWrapper.logAppendValue]，实时把 [LogWrapper.logAppend]
 * 收到的最新一条消息显示在屏幕指定位置；每次新消息弹出后停留数秒自动消失，期间再来新消息则重置计时。
 * 停留时长可在 1–30 秒间设置；开启「常驻显示」后新消息不再自动消失。
 * 消息过滤：仅当消息文本包含关键词"步骤"或"任务"时才显示（设置预览不受影响）。
 *
 * 窗口类型优先使用无障碍服务类型 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * （由无障碍服务实例持有，无需 SYSTEM_ALERT_WINDOW 权限）；无障碍服务不可用（instance 为 null）
 * 时回退到 [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]（需要悬浮窗权限）。
 *
 * 用法：
 * - [show] 启用（开始监听日志，之后自动随日志弹出/隐藏）；
 * - [hide] 彻底关闭（停止监听并移除悬浮窗）；
 * - [showSettings] 弹出设置对话框，可开关该功能，调整悬浮窗的宽度、高度、字号、停留时长、
 *   位置（垂直顶/底+距边、水平左/中/右）、背景色与文字色，并支持「恢复默认」，带实时预览。
 */
object DynamicIslandFloatWindow {

    private const val TAG = "DynamicIslandFloatWindow"
    /** 水平「居左/居右」时距屏幕边缘的固定边距（dp），避免贴边过紧 */
    private const val HORIZONTAL_MARGIN_DP = 12
    /** 预览时显示的示例文字 */
    private const val PREVIEW_TEXT = "这是灵动岛预览效果"
    /** 关键词过滤：消息包含下列任一关键词时才显示（设置预览不受影响） */
    private val FILTER_KEYWORDS = listOf("步骤", "任务")

    private var enabled = false
    /** 是否处于「设置预览」状态：预览期间不触发自动隐藏，关闭对话框后复原 */
    private var previewing = false
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var binding: DynamicIslandFloatBinding? = null
    private var collectJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideWindow() }
    /** 时间格式：时-分-秒，用于拼接在消息前 */
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun withTime(text: String): String = "${timeFormat.format(Date())} $text"

    /** 当前悬浮窗是否正在显示（仅指可见的胶囊，不含「已启用但空闲」状态） */
    @JvmStatic
    fun isShowing(): Boolean = floatingView != null

    /** 启用灵动岛：开始监听 logAppend，之后每次新日志自动弹出并在数秒后消失 */
    @JvmStatic
    @JvmOverloads
    fun show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show() }
            return
        }
        if (enabled) return
        enabled = true
        startCollect()
    }

    /**
     * 自动初始化：在服务就绪或应用重启后调用。
     * 仅当持久化的开关为开启状态时，才启用监听并开始工作；开关关闭则不显示悬浮窗。
     * 可重复调用，[show] 内部保证幂等。
     */
    @JvmStatic
    fun autoInit() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { autoInit() }
            return
        }
        if (!DynamicIslandStore.isEnabled()) return
        show()
    }

    /** 彻底关闭灵动岛：停止监听并移除悬浮窗 */
    @JvmStatic
    @JvmOverloads
    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hide() }
            return
        }
        enabled = false
        previewing = false
        collectJob?.cancel()
        collectJob = null
        hideWindow()
    }

    private fun startCollect() {
        if (collectJob != null) return
        collectJob = CoroutineWrapper.launch {
            LogWrapper.logAppendValue.collect { pair ->
                val msg = extractMsg(pair.first)
                if (msg.isBlank()) return@collect
                withContext(Dispatchers.Main) { update(msg) }
            }
        }
    }

    /** raw = "\n时间\n消息"，去掉前置换行与时间行，仅保留最新消息文本 */
    private fun extractMsg(raw: String): String {
        val noLeading = raw.dropWhile { it == '\n' }
        val idx = noLeading.indexOf('\n')
        return if (idx >= 0) noLeading.substring(idx + 1) else noLeading
    }

    private fun update(msg: String) {
        if (!enabled) return
        if (FILTER_KEYWORDS.none { msg.contains(it) }) return
        ensureWindow() ?: return
        binding?.tvIslandText?.text = withTime(msg)
        // 预览期间不触发自动隐藏，保持可见；常驻模式也不自动隐藏；
        // 其余情况按停留时长倒计时后消失
        if (!previewing && !DynamicIslandStore.isPersistent()) {
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, DynamicIslandStore.getDurationSec() * 1000L)
        }
    }

    private fun ensureWindow(): Boolean {
        if (floatingView != null) return true
        val svc = SelectToSpeakServiceAbstract.instance
        val ctx: Context = svc ?: appContext
        val windowType = resolveWindowType(ctx, svc) ?: return false
        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = DynamicIslandFloatBinding.inflate(LayoutInflater.from(ctx))
        binding = b
        floatingView = b.root
        val dm = ctx.resources.displayMetrics
        val widthPx = (DynamicIslandStore.getWidthDp().coerceAtMost(screenWidthDp(dm)) * dm.density).toInt()
        val heightPx = (DynamicIslandStore.getHeightDp() * dm.density).toInt()
        val (gravity, x, y) = computePosition(dm)
        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
        try {
            windowManager?.addView(floatingView, layoutParams)
            Log.i(TAG, "灵动岛显示，type=$windowType")
        } catch (e: Exception) {
            Log.e(TAG, "灵动岛显示失败: ${e.message}")
            cleanup()
            return false
        }
        applyAppearanceToView()
        return true
    }

    /** 屏幕宽度（dp） */
    private fun screenWidthDp(dm: android.util.DisplayMetrics): Int =
        (dm.widthPixels / dm.density).toInt()

    /** 根据存储的位置偏好计算 gravity 与 x/y 偏移（px） */
    private fun computePosition(dm: android.util.DisplayMetrics): Triple<Int, Int, Int> {
        var gravity = Gravity.NO_GRAVITY
        var x = 0
        var y = 0
        val vMargin = (DynamicIslandStore.getVMarginDp() * dm.density).toInt()
        val hMargin = (HORIZONTAL_MARGIN_DP * dm.density).toInt()
        when (DynamicIslandStore.getVertical()) {
            "bottom" -> { gravity = gravity or Gravity.BOTTOM; y = vMargin }
            else -> { gravity = gravity or Gravity.TOP; y = vMargin }
        }
        when (DynamicIslandStore.getHorizontal()) {
            "left" -> { gravity = gravity or Gravity.START; x = hMargin }
            "right" -> { gravity = gravity or Gravity.END; x = -hMargin }
            else -> { gravity = gravity or Gravity.CENTER_HORIZONTAL; x = 0 }
        }
        return Triple(gravity, x, y)
    }

    /** 把持久化的宽度 / 高度 / 字号应用到已显示的悬浮窗 */
    private fun applySizeToView() {
        val b = binding ?: return
        val lp = layoutParams ?: return
        val dm = b.root.resources.displayMetrics
        lp.width = (DynamicIslandStore.getWidthDp().coerceAtMost(screenWidthDp(dm)) * dm.density).toInt()
        lp.height = (DynamicIslandStore.getHeightDp() * dm.density).toInt()
        b.tvIslandText.setTextSize(TypedValue.COMPLEX_UNIT_SP, DynamicIslandStore.getTextSizeSp().toFloat())
        windowManager?.updateViewLayout(b.root, lp)
    }

    /** 把持久化的位置（垂直 / 水平 / 距边）应用到已显示的悬浮窗 */
    private fun applyPositionToView() {
        val b = binding ?: return
        val lp = layoutParams ?: return
        val dm = b.root.resources.displayMetrics
        val (gravity, x, y) = computePosition(dm)
        lp.gravity = gravity
        lp.x = x
        lp.y = y
        windowManager?.updateViewLayout(b.root, lp)
    }

    /** 把持久化的背景色 / 文字色应用到已显示的悬浮窗（背景用胶囊圆角 drawable） */
    private fun applyAppearanceToView() {
        val b = binding ?: return
        val dm = b.root.resources.displayMetrics
        val bgColor = DynamicIslandStore.getBgColor()
        val textColor = DynamicIslandStore.getTextColor()
        val pill = GradientDrawable()
        pill.setColor(bgColor)
        // 圆角取高度一半，保持胶囊形状
        pill.cornerRadius = (DynamicIslandStore.getHeightDp() * dm.density) / 2f
        b.root.background = pill
        b.tvIslandText.setTextColor(textColor)
    }

    /** 在顶部显示一条预览文字并持续可见（不自动隐藏），供设置对话框实时预览 */
    private fun previewIsland(text: String = PREVIEW_TEXT) {
        if (!enabled) return
        ensureWindow() ?: return
        previewing = true
        handler.removeCallbacks(hideRunnable)
        binding?.tvIslandText?.text = withTime(text)
        // 确保预览立即反映最新尺寸/位置/外观
        applySizeToView()
        applyPositionToView()
        applyAppearanceToView()
    }

    /** 结束预览：移除预览窗（保留监听，若已启用则下次真实日志会再次弹出） */
    private fun stopPreview() {
        if (!previewing) return
        previewing = false
        handler.removeCallbacks(hideRunnable)
        hideWindow()
    }

    private fun hideWindow() {
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) { }
        }
        cleanup()
    }

    private fun cleanup() {
        floatingView = null
        binding = null
        layoutParams = null
        windowManager = null
        handler.removeCallbacks(hideRunnable)
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
                AliveUtils.toast(msg = "请开启悬浮窗权限以显示灵动岛")
                null
            }
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /**
     * 弹出「灵动岛设置」对话框：开关是否启用，调整悬浮窗宽度 / 高度 / 字号 / 停留时长，
     * 以及位置（垂直顶/底+距边、水平左/中/右），并带实时预览。
     * 可在任意线程调用（内部自动切主线程）。
     * @param context 上下文（Activity 或无障碍服务均可；非 Activity 时对话框以无障碍浮层形式显示）
     */
    @JvmStatic
    @JvmOverloads
    fun showSettings(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showSettings(context) }
            return
        }
        val b = DialogDynamicIslandSettingsBinding.inflate(LayoutInflater.from(context))

        var enabledNow = DynamicIslandStore.isEnabled()
        var width = DynamicIslandStore.getWidthDp()
        var height = DynamicIslandStore.getHeightDp()
        var textSize = DynamicIslandStore.getTextSizeSp()
        var duration = DynamicIslandStore.getDurationSec()
        var persistent = DynamicIslandStore.isPersistent()
        var vmargin = DynamicIslandStore.getVMarginDp()

        b.swEnabled.isChecked = enabledNow
        // 宽度滑块最大值取设备屏幕实际宽度（dp），以便可拖到铺满屏幕
        val screenWdp = (context.resources.displayMetrics.widthPixels
                / context.resources.displayMetrics.density).toInt()
        val widthMax = (screenWdp - DynamicIslandStore.MIN_WIDTH_DP).coerceAtLeast(0)
        // SeekBar 不支持负向起点，故用 0..(max-min) 的进度，实际值 = 进度 + 下限
        b.sbWidth.max = widthMax
        b.sbWidth.progress = (width - DynamicIslandStore.MIN_WIDTH_DP).coerceIn(0, widthMax)
        b.sbHeight.max = DynamicIslandStore.MAX_HEIGHT_DP - DynamicIslandStore.MIN_HEIGHT_DP
        b.sbHeight.progress = height - DynamicIslandStore.MIN_HEIGHT_DP
        b.sbTextSize.max = DynamicIslandStore.MAX_TEXT_SIZE_SP - DynamicIslandStore.MIN_TEXT_SIZE_SP
        b.sbTextSize.progress = textSize - DynamicIslandStore.MIN_TEXT_SIZE_SP
        b.sbDuration.max = DynamicIslandStore.MAX_DURATION_SEC - DynamicIslandStore.MIN_DURATION_SEC
        b.sbDuration.progress = duration - DynamicIslandStore.MIN_DURATION_SEC
        b.sbVmargin.max = DynamicIslandStore.MAX_VMARGIN_DP - DynamicIslandStore.MIN_VMARGIN_DP
        b.sbVmargin.progress = vmargin - DynamicIslandStore.MIN_VMARGIN_DP

        b.tvWidthValue.text = "$width dp"
        b.tvHeightValue.text = "$height dp"
        b.tvTextsizeValue.text = "$textSize sp"
        b.tvDurationValue.text = "$duration 秒"
        b.tvVmarginValue.text = "$vmargin dp"
        // 「显示时长」合并为单选：常驻 / 定时；选常驻时隐藏停留时长滑块
        if (persistent) b.rgDisplayMode.check(R.id.rb_persistent)
        else b.rgDisplayMode.check(R.id.rb_timed)
        b.layoutDuration.visibility = if (persistent) View.GONE else View.VISIBLE

        when (DynamicIslandStore.getVertical()) {
            "bottom" -> b.rgVertical.check(R.id.rb_bottom)
            else -> b.rgVertical.check(R.id.rb_top)
        }
        when (DynamicIslandStore.getHorizontal()) {
            "left" -> b.rgHorizontal.check(R.id.rb_left)
            "right" -> b.rgHorizontal.check(R.id.rb_right)
            else -> b.rgHorizontal.check(R.id.rb_center)
        }

        // 颜色色块初始化
        b.swatchBg.setBackgroundColor(DynamicIslandStore.getBgColor())
        b.swatchText.setBackgroundColor(DynamicIslandStore.getTextColor())
        b.rowBgColor.setOnClickListener {
            openColorPicker(context, "背景颜色", DynamicIslandStore.getBgColor()) { c ->
                DynamicIslandStore.setBgColor(c)
                b.swatchBg.setBackgroundColor(c)
                if (isShowing()) applyAppearanceToView()
            }
        }
        b.rowTextColor.setOnClickListener {
            openColorPicker(context, "文字颜色", DynamicIslandStore.getTextColor()) { c ->
                DynamicIslandStore.setTextColor(c)
                b.swatchText.setBackgroundColor(c)
                if (isShowing()) applyAppearanceToView()
            }
        }
        b.btnResetDefault.setOnClickListener {
            DynamicIslandStore.resetToDefaults()
            // 重新读取并刷新所有控件到默认值
            width = DynamicIslandStore.getWidthDp()
            height = DynamicIslandStore.getHeightDp()
            textSize = DynamicIslandStore.getTextSizeSp()
            duration = DynamicIslandStore.getDurationSec()
            vmargin = DynamicIslandStore.getVMarginDp()
            b.sbWidth.progress = (width - DynamicIslandStore.MIN_WIDTH_DP).coerceIn(0, widthMax)
            b.sbHeight.progress = height - DynamicIslandStore.MIN_HEIGHT_DP
            b.sbTextSize.progress = textSize - DynamicIslandStore.MIN_TEXT_SIZE_SP
            b.sbDuration.progress = duration - DynamicIslandStore.MIN_DURATION_SEC
            b.sbVmargin.progress = vmargin - DynamicIslandStore.MIN_VMARGIN_DP
            b.tvWidthValue.text = "$width dp"
            b.tvHeightValue.text = "$height dp"
            b.tvTextsizeValue.text = "$textSize sp"
            b.tvDurationValue.text = "$duration 秒"
            b.tvVmarginValue.text = "$vmargin dp"
            persistent = DynamicIslandStore.isPersistent()
            if (persistent) b.rgDisplayMode.check(R.id.rb_persistent)
            else b.rgDisplayMode.check(R.id.rb_timed)
            b.layoutDuration.visibility = if (persistent) View.GONE else View.VISIBLE
            if (DynamicIslandStore.getVertical() == "bottom") b.rgVertical.check(R.id.rb_bottom)
            else b.rgVertical.check(R.id.rb_top)
            when (DynamicIslandStore.getHorizontal()) {
                "left" -> b.rgHorizontal.check(R.id.rb_left)
                "right" -> b.rgHorizontal.check(R.id.rb_right)
                else -> b.rgHorizontal.check(R.id.rb_center)
            }
            b.swatchBg.setBackgroundColor(DynamicIslandStore.getBgColor())
            b.swatchText.setBackgroundColor(DynamicIslandStore.getTextColor())
            if (isShowing()) {
                applySizeToView()
                applyPositionToView()
                applyAppearanceToView()
            }
        }

        // 关键：若持久化已启用（典型场景：重启应用后单例内存重置为关闭，但 store 仍为开启），
        // 必须在此同步内存状态（enabled=true + 启动监听）并立即预览，否则拖动滑块时
        // previewIsland() 会因 enabled=false 直接 return，导致看不到预览。
        if (enabledNow) {
            show()
            previewIsland()
        }

        /** 尺寸变化后刷新预览（已显示则直接重设，未显示则弹出预览） */
        fun refreshSize() {
            if (enabledNow) {
                if (isShowing()) applySizeToView() else previewIsland()
            }
        }

        /** 位置变化后刷新预览 */
        fun refreshPosition() {
            if (enabledNow) {
                if (isShowing()) applyPositionToView() else previewIsland()
            }
        }

        b.swEnabled.setOnCheckedChangeListener { _: android.widget.CompoundButton?, isOn: Boolean ->
            enabledNow = isOn
            DynamicIslandStore.setEnabled(isOn)
            if (isOn) show() else hide()
            if (isOn) previewIsland() else stopPreview()
        }

        b.rgDisplayMode.setOnCheckedChangeListener { _: RadioGroup?, checkedId: Int ->
            val isPersistent = checkedId == R.id.rb_persistent
            persistent = isPersistent
            DynamicIslandStore.setPersistent(isPersistent)
            // 选「常驻不消失」时隐藏停留时长滑块；选「定时消失」时恢复
            b.layoutDuration.visibility = if (isPersistent) View.GONE else View.VISIBLE
            if (!isPersistent) b.tvDurationValue.text = "$duration 秒"
            // 开启常驻：取消待执行的隐藏任务，让当前已显示的消息保持可见
            if (isPersistent && enabledNow && isShowing()) {
                handler.removeCallbacks(hideRunnable)
            }
        }

        b.sbWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                width = (p + DynamicIslandStore.MIN_WIDTH_DP).coerceIn(
                    DynamicIslandStore.MIN_WIDTH_DP, DynamicIslandStore.MAX_WIDTH_DP
                )
                b.tvWidthValue.text = "$width dp"
                DynamicIslandStore.setWidthDp(width)
                refreshSize()
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        b.sbHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                height = (p + DynamicIslandStore.MIN_HEIGHT_DP).coerceIn(
                    DynamicIslandStore.MIN_HEIGHT_DP, DynamicIslandStore.MAX_HEIGHT_DP
                )
                b.tvHeightValue.text = "$height dp"
                DynamicIslandStore.setHeightDp(height)
                refreshSize()
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        b.sbTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                textSize = (p + DynamicIslandStore.MIN_TEXT_SIZE_SP).coerceIn(
                    DynamicIslandStore.MIN_TEXT_SIZE_SP, DynamicIslandStore.MAX_TEXT_SIZE_SP
                )
                b.tvTextsizeValue.text = "$textSize sp"
                DynamicIslandStore.setTextSizeSp(textSize)
                refreshSize()
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        b.sbDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                duration = (p + DynamicIslandStore.MIN_DURATION_SEC).coerceIn(
                    DynamicIslandStore.MIN_DURATION_SEC, DynamicIslandStore.MAX_DURATION_SEC
                )
                b.tvDurationValue.text = "$duration 秒"
                DynamicIslandStore.setDurationSec(duration)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        b.sbVmargin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                vmargin = (p + DynamicIslandStore.MIN_VMARGIN_DP).coerceIn(
                    DynamicIslandStore.MIN_VMARGIN_DP, DynamicIslandStore.MAX_VMARGIN_DP
                )
                b.tvVmarginValue.text = "$vmargin dp"
                DynamicIslandStore.setVMarginDp(vmargin)
                refreshPosition()
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })

        b.rgVertical.setOnCheckedChangeListener { _: RadioGroup?, checkedId: Int ->
            val v = if (checkedId == R.id.rb_bottom) "bottom" else "top"
            DynamicIslandStore.setVertical(v)
            refreshPosition()
        }

        b.rgHorizontal.setOnCheckedChangeListener { _: RadioGroup?, checkedId: Int ->
            val h = when (checkedId) {
                R.id.rb_left -> "left"
                R.id.rb_right -> "right"
                else -> "center"
            }
            DynamicIslandStore.setHorizontal(h)
            refreshPosition()
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("灵动岛设置")
            .setView(b.root)
            .setPositiveButton("完成", null)
            .create()
        dialog.setOnDismissListener {
            // 退出设置：结束预览；若已关闭功能则确保悬浮窗移除
            stopPreview()
            if (!enabledNow) hide()
        }
        // 非 Activity 上下文（如无障碍服务）需指定窗口类型，否则对话框无法附着
        if (context.findActivity() == null) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        }
        dialog.show()

        // 小屏保护：若内容超高，把对话框窗口高度限制在「屏幕高 - 预留」以内，
        // ScrollView(match_parent) 随之变为可滚动，避免「完成」按钮/底部项被挤出屏幕。
        val win = dialog.window ?: return
        val dmGuard = context.resources.displayMetrics
        val reservePx = (200 * dmGuard.density).toInt() // 标题 + 完成按钮 + 边距预留
        val maxH = (dmGuard.heightPixels - reservePx).coerceAtLeast((dmGuard.heightPixels * 0.5).toInt())
        val inner = (b.root as? ViewGroup)?.getChildAt(0)
        if (inner != null) {
            inner.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val contentH = inner.measuredHeight
            val chromePx = (110 * dmGuard.density).toInt() // 标题 + 完成按钮高度估算
            val desired = (contentH + chromePx).coerceAtMost(maxH)
            val lp = win.attributes
            lp.height = desired
            win.attributes = lp
        }
    }

    /** 弹出 ARGB 取色器；onPick 返回选中的颜色（ARGB int） */
    private fun openColorPicker(context: Context, title: String, initial: Int, onPick: (Int) -> Unit) {
        val pb = DialogColorPickerBinding.inflate(LayoutInflater.from(context))
        var cur = initial
        fun refresh(color: Int) {
            pb.swatch.setBackgroundColor(color)
            pb.tvHex.text = String.format("#%08X", color)
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                cur = ((pb.sbAlpha.progress shl 24)
                        or (pb.sbRed.progress shl 16)
                        or (pb.sbGreen.progress shl 8)
                        or pb.sbBlue.progress)
                refresh(cur)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        pb.sbAlpha.max = 255; pb.sbAlpha.progress = (initial shr 24) and 0xFF
        pb.sbRed.max = 255; pb.sbRed.progress = (initial shr 16) and 0xFF
        pb.sbGreen.max = 255; pb.sbGreen.progress = (initial shr 8) and 0xFF
        pb.sbBlue.max = 255; pb.sbBlue.progress = initial and 0xFF
        pb.sbAlpha.setOnSeekBarChangeListener(listener)
        pb.sbRed.setOnSeekBarChangeListener(listener)
        pb.sbGreen.setOnSeekBarChangeListener(listener)
        pb.sbBlue.setOnSeekBarChangeListener(listener)
        refresh(initial)
        val dlg = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(pb.root)
            .setPositiveButton("确定") { _, _ -> onPick(cur) }
            .setNegativeButton("取消", null)
            .create()
        if (context.findActivity() == null) {
            dlg.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        }
        dlg.show()
    }

    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
