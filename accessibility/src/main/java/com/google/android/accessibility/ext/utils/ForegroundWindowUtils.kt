package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getScreenSize
import kotlin.collections.asSequence
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.collections.isNullOrEmpty
import kotlin.collections.maxByOrNull
import kotlin.let
import kotlin.sequences.filter
import kotlin.sequences.firstOrNull
import kotlin.sequences.mapNotNull

object ForegroundWindowUtils {

    private var lastFocusedWindowId: Int? = null
    private var lastFocusChangeTime = 0L
    private const val FOCUS_CHANGE_DELAY = 150L // 防抖 ms
    private const val SCROLL_END_DELAY = 100L // 滚动结束延迟 ms

    private val defaultBlackTypes = listOf(
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_SYSTEM,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
        AccessibilityWindowInfo.TYPE_INPUT_METHOD
    )
    @JvmField
    val defaultEmpty = listOf(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)

    // 评分权重
    private const val FULL_SCREEN_SCORE = 1000
    private const val FOCUS_SCORE = 500
    private const val FOCUS_WINDOW_BOOST = 1_000_000
    private const val MAIN_SPLIT_BOOST = 5000 // 主窗口额外加分

    private val rectCache = Rect()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCallback: (() -> Unit)? = null

    private var scrollActive = false

    /** 获取前台窗口包名（分屏主副窗口 + 滚动防抖） */
    @JvmStatic
    @JvmOverloads
    fun getForegroundPackageName(
        service: AccessibilityService,
        blackTypeList: List<Int> = defaultBlackTypes
    ): String? {
        val windows = service.windows ?: return null
        val (screenWidth, screenHeight) = getScreenSize()
        val safeRect = Rect(0, 0, screenWidth, screenHeight)

        val insets = getDisplaySafeInsets(service)
        safeRect.left += insets.left
        safeRect.top += insets.top
        safeRect.right -= insets.right
        safeRect.bottom -= insets.bottom

        val splitInfo = getSplitScreenInfo(service)

        var candidateWindow: AccessibilityWindowInfo? = null
        var candidateScore = -1

        windows.filter { it.type !in blackTypeList }.forEach { window ->
            window.getBoundsInScreen(rectCache)
            val width = rectCache.width()
            val height = rectCache.height()
            val area = width * height
            val isFocused = window.isFocused || window.isActive

            // 覆盖率计算
            val coverRatio = area.toFloat() / (safeRect.width() * safeRect.height())

            // 分屏权重微调
            val splitFactor = if (splitInfo.isSplitScreen) {
                if (splitInfo.orientation == SplitOrientation.HORIZONTAL) {
                    width.toFloat() / screenWidth
                } else {
                    height.toFloat() / screenHeight
                }
            } else 1f

            // 主窗口额外加分（窗口占比最大或焦点窗口）
            val mainSplitBoost = if (splitInfo.isSplitScreen && isSplitMainWindow(window, splitInfo, screenWidth, screenHeight)) {
                MAIN_SPLIT_BOOST
            } else 0

            val fullScreenScore = if (coverRatio * splitFactor > 0.6f) FULL_SCREEN_SCORE else 0
            val focusScore = if (isFocused) FOCUS_SCORE else 0
            val score = fullScreenScore + focusScore + area / 1000 + mainSplitBoost

            if (score > candidateScore) {
                candidateWindow = window
                candidateScore = score
            }
        }

        return candidateWindow?.root?.packageName?.toString()
            ?: windows
                .asSequence()
                .filter { it.type !in blackTypeList }
                .mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull()
    }

    /** 判断焦点窗口是否变化（防抖 + 滚动结束延迟） */
    @JvmStatic
    @JvmOverloads
    fun hasFocusedWindowChanged(
        service: AccessibilityService,
        blackTypeList: List<Int> = defaultBlackTypes
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastFocusChangeTime < FOCUS_CHANGE_DELAY) return false

        if (scrollActive) return false // 滚动中不触发

        val windows = service.windows ?: return false

        val focusedWindow = windows
            .filter { it.type !in blackTypeList }
            .maxByOrNull {
                it.getWidth() * it.getHeight() + if (it.isFocused || it.isActive) FOCUS_WINDOW_BOOST else 0
            }

        focusedWindow?.let {
            if (lastFocusedWindowId != it.id) {
                lastFocusedWindowId = it.id
                lastFocusChangeTime = now
                return true
            }
        }

        return false
    }

    /** 滚动事件开始调用 */
    @JvmStatic
    @JvmOverloads
    fun onScrollStart() {
        scrollActive = true
        pendingCallback?.let { handler.removeCallbacks(it) }
    }

    /** 滚动事件结束调用（延迟确认） */
    @JvmStatic
    @JvmOverloads
    fun onScrollEnd(callback: () -> Unit) {
        scrollActive = false
        pendingCallback?.let { handler.removeCallbacks(it) }
        pendingCallback = callback
        handler.postDelayed(callback, SCROLL_END_DELAY)
    }

    /** 延迟触发焦点变化回调 */
    @JvmStatic
    fun postFocusChangeCallback(callback: () -> Unit) {
        pendingCallback?.let { handler.removeCallbacks(it) }
        pendingCallback = callback
        handler.postDelayed(callback, FOCUS_CHANGE_DELAY)
    }

    /** 重置缓存 */
    @JvmStatic
    fun resetFocusWindow() {
        lastFocusedWindowId = null
        lastFocusChangeTime = 0
        pendingCallback?.let { handler.removeCallbacks(it) }
        pendingCallback = null
        scrollActive = false
    }

    /** 扩展函数 */
    private fun AccessibilityWindowInfo.getWidth(): Int {
        this.getBoundsInScreen(rectCache)
        return rectCache.width()
    }

    private fun AccessibilityWindowInfo.getHeight(): Int {
        this.getBoundsInScreen(rectCache)
        return rectCache.height()
    }

    /** 安全区 Insets */
    private fun getDisplaySafeInsets(service: AccessibilityService): Rect {
        val insets = Rect(0, 0, 0, 0)
        insets.bottom = getNavigationBarHeight(service)
        insets.top = 0
        return insets
    }

    private fun getNavigationBarHeight(service: AccessibilityService): Int {
        val res = service.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        val nav = if (id > 0) res.getDimensionPixelSize(id) else 0
        return if (nav == 0) (24 * res.displayMetrics.density).toInt() else nav
    }

    /** 分屏信息数据类 */
    private data class SplitScreenInfo(
        val isSplitScreen: Boolean,
        val orientation: SplitOrientation
    )

    private enum class SplitOrientation { HORIZONTAL, VERTICAL }

    /** 获取分屏状态和方向 */
    private fun getSplitScreenInfo(service: AccessibilityService): SplitScreenInfo {
        val dividerWindow = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
            ?: return SplitScreenInfo(false, SplitOrientation.HORIZONTAL)
        val rect = Rect()
        dividerWindow.getBoundsInScreen(rect)
        val orientation = if (rect.width() > rect.height()) SplitOrientation.HORIZONTAL else SplitOrientation.VERTICAL
        return SplitScreenInfo(true, orientation)
    }

    /** 判断系统是否分屏 */
    @JvmStatic
    fun isSystemInSplitScreen(service: AccessibilityService): Boolean {
        return getSplitScreenInfo(service).isSplitScreen
    }

    /** 分屏模式下判断主窗口 */
    private fun isSplitMainWindow(
        window: AccessibilityWindowInfo,
        splitInfo: SplitScreenInfo,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (!splitInfo.isSplitScreen) return true
        window.getBoundsInScreen(rectCache)
        return if (splitInfo.orientation == SplitOrientation.HORIZONTAL) {
            // 左右分屏：宽度更大或靠左偏移可认为主窗口
            rectCache.width() >= screenWidth / 2
        } else {
            // 上下分屏：高度更大或靠上可认为主窗口
            rectCache.height() >= screenHeight / 2
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    fun isKeyguardForeground(service: AccessibilityService): Boolean {

        // =========================
        // 🔥 0. 系统级短路（最高优先级）
        // =========================
        val km = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            return true
        }

        // ===== 以下才是无障碍推断 =====

        val activeRoot = service.rootInActiveWindow
        val activePkg = activeRoot?.packageName?.toString()
        if (activePkg != "com.android.systemui") return false

        val windows = service.windows ?: return false

        val target = windows.firstOrNull { it?.isActive == true && it.isFocused }
            ?: windows.firstOrNull { it?.isActive == true }
            ?: return false

        val windowRoot = target.root
        val windowPkg = windowRoot?.packageName?.toString()

        if (windowPkg != null && windowPkg != activePkg) return false
        if (target.type == AccessibilityWindowInfo.TYPE_SYSTEM) return false

        val rect = Rect()
        target.getBoundsInScreen(rect)

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds

        val isFullScreen =
            rect.width() >= bounds.width() * 0.9 &&
                    rect.height() >= bounds.height() * 0.9

        if (!isFullScreen) return false

        val root = windowRoot ?: activeRoot
        val keywords = arrayOf("解锁", "上滑", "unlock", "swipe", "PIN", "密码")

        for (k in keywords) {
            val list = root?.findAccessibilityNodeInfosByText(k)
            if (!list.isNullOrEmpty()) return true
        }

        return true
    }

}