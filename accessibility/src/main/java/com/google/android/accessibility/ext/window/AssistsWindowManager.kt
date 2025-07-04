package com.google.android.accessibility.ext.window
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.google.android.accessibility.ext.CoroutineWrapper
import com.google.android.accessibility.ext.runIO
import com.google.android.accessibility.ext.runMain
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.SizeUtils


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * 浮窗管理器
 * 提供全局浮窗的添加、删除、显示、隐藏等管理功能
 */
object AssistsWindowManager {
    /** 系统窗口管理器 */
    private lateinit var windowManager: WindowManager
    /** 显示度量信息 */
    private lateinit var mDisplayMetrics: DisplayMetrics
    /** 浮窗视图列表，使用线程安全的集合 */
    private val viewList = Collections.synchronizedList(arrayListOf<ViewWrapper>())

    /**
     * 初始化窗口管理器
     * @param accessibilityService 无障碍服务实例
     */
    fun init(accessibilityService: AccessibilityService) {
        windowManager = accessibilityService.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mDisplayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(mDisplayMetrics)
    }

    /**
     * 获取系统窗口管理器实例
     * @return WindowManager实例，如果未初始化则返回null
     */
    fun getWindowManager(): WindowManager? {
        SelectToSpeakServiceAbstract.instance?.getSystemService(Context.WINDOW_SERVICE)?.let { return (it as WindowManager) }
        return null
    }

    /**
     * 创建默认的浮窗布局参数
     * @return 配置好的WindowManager.LayoutParams实例
     */
    fun createLayoutParams(): WindowManager.LayoutParams {
        val layoutParams = WindowManager.LayoutParams()
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.gravity = Gravity.START or Gravity.TOP
        layoutParams.format = PixelFormat.RGBA_8888
        //此处layoutParams.type不建议使用TYPE_TOAST，因为在一些版本较低的系统中会出现拖动异常的问题，虽然它不需要权限
        layoutParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        //背景明暗度0~1，数值越大背景越暗，只有在flags设置了WindowManager.LayoutParams.FLAG_DIM_BEHIND 这个属性才会生效
        layoutParams.dimAmount = 0.0f
        //透明度0~1，数值越大越不透明
        layoutParams.alpha = 1f
        return layoutParams
    }

    /**
     * 隐藏所有浮窗
     * @param isTouchable 隐藏后是否可触摸，默认为true
     */
    suspend fun hideAll(isTouchable: Boolean = true) {
        withContext(Dispatchers.Main) {
            viewList.forEach {
                it.view.isInvisible = true
                if (isTouchable) {
                    it.touchableByWrapper()
                } else {
                    it.nonTouchableByWrapper()
                }
            }
        }
    }

    /**
     * 隐藏最顶层浮窗
     * @param isTouchable 隐藏后是否可触摸，默认为true
     */
    suspend fun hideTop(isTouchable: Boolean = true) {
        withContext(Dispatchers.Main) {
            viewList.lastOrNull()?.let {
                it.view.isInvisible = true
                if (isTouchable) {
                    it.touchableByWrapper()
                } else {
                    it.nonTouchableByWrapper()
                }
            }
        }
    }

    /**
     * 显示最顶层浮窗
     * @param isTouchable 显示后是否可触摸，默认为true
     */
    suspend fun showTop(isTouchable: Boolean = true) {
        withContext(Dispatchers.Main) {
            viewList.lastOrNull()?.let {
                it.view.isVisible = true
                if (isTouchable) {
                    it.touchableByWrapper()
                } else {
                    it.nonTouchableByWrapper()
                }
            }
        }
    }

    /**
     * 显示所有浮窗
     * @param isTouchable 显示后是否可触摸，默认为true
     */
    suspend fun showAll(isTouchable: Boolean = true) {
        withContext(Dispatchers.Main) {
            viewList.forEach {
                it.view.isVisible = true
                if (isTouchable) {
                    it.touchableByWrapper()
                } else {
                    it.nonTouchableByWrapper()
                }
            }
        }
    }

    /**
     * 添加浮窗包装器
     * @param windowWrapper 浮窗包装器
     * @param isStack 是否堆叠显示，默认为true
     * @param isTouchable 是否可触摸，默认为true
     */
    fun add(windowWrapper: AssistsWindowWrapper?, isStack: Boolean = true, isTouchable: Boolean = true) {
        windowWrapper ?: return
        add(view = windowWrapper.getView(), layoutParams = windowWrapper.wmlp, isStack = isStack, isTouchable = isTouchable)
    }

    /**
     * 添加浮窗视图
     * @param view 要添加的视图
     * @param layoutParams 布局参数
     * @param isStack 是否堆叠显示，默认为true
     * @param isTouchable 是否可触摸，默认为true
     */
    fun add(view: View?, layoutParams: WindowManager.LayoutParams = createLayoutParams(), isStack: Boolean = true, isTouchable: Boolean = true) {
        view ?: return
        if (!isStack) {
            viewList.lastOrNull()?.let { it.view.isInvisible = true }
        }
        windowManager.addView(view, layoutParams)
        if (isTouchable) {
            layoutParams.touchableByLayoutParams()
        } else {
            layoutParams.nonTouchableByLayoutParams()
        }
        viewList.add(ViewWrapper(view, layoutParams))
    }

    /**
     * 添加浮窗并隐藏之前的浮窗
     * @param view 要添加的视图
     * @param params 布局参数
     */
    fun push(view: View?, params: WindowManager.LayoutParams = createLayoutParams()) {
        add(view, params, isStack = false)
    }

    /**
     * 移除最顶层浮窗并显示下一个浮窗
     * @param showTop 是否显示下一个浮窗，默认为true
     */
    suspend fun pop(showTop: Boolean = true) {
        viewList.lastOrNull()?.let { removeView(it.view) }
        if (showTop) showTop()
    }

    /**
     * 移除指定浮窗
     * @param view 要移除的视图
     */
    fun removeView(view: View?) {
        view ?: return
        try {
            windowManager.removeView(view)
            viewList.find {
                return@find view == it.view
            }?.let {
                viewList.remove(it)
            }
        } catch (e: Throwable) {
            LogUtils.e(e)
        }
    }

    /**
     * 检查指定视图是否已添加为浮窗
     * @param view 要检查的视图
     * @return 是否存在于浮窗列表中
     */
    fun contains(view: View?): Boolean {
        view ?: return false
        return viewList.find {
            return@find view == it.view
        } != null
    }

    /**
     * 检查指定浮窗包装器是否已添加
     * @param wrapper 要检查的浮窗包装器
     * @return 是否存在于浮窗列表中
     */
    fun contains(wrapper: AssistsWindowWrapper?): Boolean {
        wrapper ?: return false
        return viewList.find {
            return@find wrapper.getView() == it.view
        } != null
    }

    /**
     * 检查指定浮窗是否可见
     * @param view 要检查的视图
     * @return 是否可见
     */
    fun isVisible(view: View): Boolean {
        return viewList.find {
            return@find view == it.view
        }?.let {
            return@let it.view.isVisible
        } ?: false
    }

    /**
     * 更新浮窗布局
     * @param view 要更新的视图
     * @param params 新的布局参数
     */
    suspend fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        runMain { windowManager.updateViewLayout(view, params) }
    }

    /**
     * 设置所有浮窗为可触摸状态
     */
    suspend fun touchableByAll() {
        viewList.forEach { it.touchableByWrapper() }
    }

    /**
     * 设置所有浮窗为不可触摸状态
     */
    suspend fun nonTouchableByAll() {
        viewList.forEach { it.nonTouchableByWrapper() }
    }

    /**
     * 设置布局参数为可触摸状态
     */
    fun WindowManager.LayoutParams.touchableByLayoutParams() {
        flags = (WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    }

    /**
     * 设置布局参数为不可触摸状态
     */
    fun WindowManager.LayoutParams.nonTouchableByLayoutParams() {
        flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    }

    /**
     * 设置浮窗包装器为可触摸状态
     */
    suspend fun ViewWrapper.touchableByWrapper() {
        layoutParams.touchableByLayoutParams()
        updateViewLayout(view, layoutParams)
    }

    /**
     * 设置浮窗包装器为不可触摸状态
     */
    suspend fun ViewWrapper.nonTouchableByWrapper() {
        layoutParams.nonTouchableByLayoutParams()
        updateViewLayout(view, layoutParams)
    }

    /**
     * 显示一个临时的Toast样式浮窗
     * @param delay 显示时长，默认2000毫秒
     */
    fun String.overlayToast(delay: Long = 2000) {
        SelectToSpeakServiceAbstract.instance?.let {
            CoroutineWrapper.launch(isMain = true) {
                val textView = TextView(it).apply {
                    text = this@overlayToast
                    setTextColor(Color.WHITE)
                    setPadding(SizeUtils.dp2px(10f))
                }
                val assistsWindowWrapper = AssistsWindowWrapper(textView, wmLayoutParams = createLayoutParams().apply {
                    width = -2
                    height = -2
                }).apply {
                    showOption = false
                    initialCenter = true
                }
                add(assistsWindowWrapper, isTouchable = false)
                runIO { delay(delay) }
                removeView(assistsWindowWrapper.getView())
            }
        }
    }

    /**
     * 浮窗视图包装类
     * @param view 浮窗视图
     * @param layoutParams 布局参数
     */
    class ViewWrapper(val view: View, val layoutParams: WindowManager.LayoutParams)
}