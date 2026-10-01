package com.google.android.accessibility.ext.acc

import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.gestureUtils.HumanTouchEngine
import com.google.android.accessibility.ext.utils.gestureUtils.HumanTouchEngine.SwipeConfig
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.delay

/**
 * 点击事件
 */
fun AccessibilityNodeInfo?.click(gesure: Boolean = true): Boolean {
    this ?: return false
    //===
    val nodeBounds = Rect().apply(this::getBoundsInScreen)
    // 确保边界值非负
    val x = Math.max(0, nodeBounds.centerX()).toFloat()
    val y = Math.max(0, nodeBounds.centerY()).toFloat()
    //点击轨迹提示
    accessibilityService?.let { KeyguardUnLock.showClickIndicator(it, x.toInt(), y.toInt()) }
    //===
    return if (gesure){
        accessibilityService?.gestureClick(this) == true
    }else{
        if (isClickable) {
            performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            parent?.click() == true
        }
    }
   /* return if (isClickable) {
        performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } else {
        parent?.click() == true
    }*/
}

/**
 * 长按事件
 */
fun AccessibilityNodeInfo.longClick(gesure: Boolean = true): Boolean {
    //===
    val nodeBounds = Rect().apply(this::getBoundsInScreen)
    // 确保边界值非负
    val x = Math.max(0, nodeBounds.centerX()).toFloat()
    val y = Math.max(0, nodeBounds.centerY()).toFloat()
    if (KeyguardUnLock.getShowClickIndicator()){

        //点击轨迹提示
        accessibilityService?.let { KeyguardUnLock.showClickIndicator(it, x.toInt(), y.toInt()) }

    }
    return if (gesure){
        HumanTouchEngine.longClick(x, y) == true
    }else{
        if (isClickable) {
            performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } else {
            parent.longClick()
        }
    }

    //===
/*    return if (isClickable) {
        performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    } else {
        parent.longClick()
    }*/
}

/**
 * 输入内容
 */
fun AccessibilityNodeInfo.inputText(input: String): Boolean {
    //===
    if (KeyguardUnLock.getShowClickIndicator()){
        val nodeBounds = Rect().apply(this::getBoundsInScreen)
        // 确保边界值非负
        val x = Math.max(0, nodeBounds.centerX()).toFloat()
        val y = Math.max(0, nodeBounds.centerY()).toFloat()
        //点击轨迹提示
        accessibilityService?.let { KeyguardUnLock.showClickIndicator(it, x.toInt(), y.toInt()) }

    }
    //===
    return XpqAcc.inputText(this, input)
}

fun AccessibilityNodeInfo.inputTextPaste(byClipboard: Boolean = false, input: String): Boolean {
    //===
    if (KeyguardUnLock.getShowClickIndicator()){
        val nodeBounds = Rect().apply(this::getBoundsInScreen)
        // 确保边界值非负
        val x = Math.max(0, nodeBounds.centerX()).toFloat()
        val y = Math.max(0, nodeBounds.centerY()).toFloat()
        //点击轨迹提示
        accessibilityService?.let { KeyguardUnLock.showClickIndicator(it, x.toInt(), y.toInt()) }
    }
    //===
    return XpqAcc.inputTextPaste(this, byClipboard, input)
}

fun AccessibilityNodeInfo.inputTextNew(input: String): Boolean {
    //===
    if (KeyguardUnLock.getShowClickIndicator()){
        val nodeBounds = Rect().apply(this::getBoundsInScreen)
        // 确保边界值非负
        val x = Math.max(0, nodeBounds.centerX()).toFloat()
        val y = Math.max(0, nodeBounds.centerY()).toFloat()
        //点击轨迹提示
        accessibilityService?.let { KeyguardUnLock.showClickIndicator(it, x.toInt(), y.toInt()) }
    }
    //===
    return XpqAcc.inputTextNew(this, input)
}

/**
 * 向下滚动（内容回退）
 *
 * @param useGesture true = 在节点区域内用坐标手势下滑（经 [HumanTouchEngine.swipeAsync]，
 *   双通道通用：无障碍通道走真手势，UiAutomation 通道自动转 shell swipe），适用于不支持
 *   ACTION_SCROLL_BACKWARD 的容器（如部分 WebView/自绘列表）；false = 节点动作。
 * @param gestureDurationMs 手势滑动时长（仅 useGesture=true 时生效）。
 */
suspend fun AccessibilityNodeInfo.scrollBackward(
    useGesture: Boolean = false,
    gestureDurationMs: Long = 300 + HumanTouchEngine.randomDelayMs(50,50),
): Boolean {
    if (useGesture) {
        val bounds = Rect().also(this::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val cx = bounds.centerX().toFloat()
        // 手指由上往下滑 = 内容向上（向后）滚动；起止点各留 20% 边距避免滑出节点边界
        val startY = bounds.top + bounds.height() * 0.2f
        val endY = bounds.bottom - bounds.height() * 0.2f
        return HumanTouchEngine.swipeAsync(
            PointF(cx, startY),
            PointF(cx, endY),
            SwipeConfig(durationMeanMs = gestureDurationMs.toDouble())
        )
    }
    return performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
}

/**
 * 向上滚动
 *
 * @param useGesture true = 在节点区域内用坐标手势上滑（经 [XpqAcc.dispatchGesture]，
 *   双通道通用：无障碍通道走真手势，UiAutomation 通道自动转 shell swipe），适用于不支持
 *   ACTION_SCROLL_FORWARD 的容器（如部分 WebView/自绘列表）；false = 节点动作，失败后延迟 1s 重试一次。
 * @param gestureDurationMs 手势滑动时长（仅 useGesture=true 时生效）。
 */
suspend fun AccessibilityNodeInfo.scrollForward(
    useGesture: Boolean = false,
    gestureDurationMs: Long = 300 + HumanTouchEngine.randomDelayMs(50,50),
): Boolean {
    if (useGesture) {
        val bounds = Rect().also(this::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val cx = bounds.centerX().toFloat()
        // 手指由下往上滑 = 内容向下（向前）滚动；起止点各留 20% 边距避免滑出节点边界
        val startY = bounds.bottom - bounds.height() * 0.3f
        val endY = bounds.top + bounds.height() * 0.3f
        // 复用拟人化引擎：贝塞尔弧线 + 变速 + 噪点，经 XpqAcc.dispatchGesture 双通道派发
        return HumanTouchEngine.swipeAsync(
            PointF(cx, startY),
            PointF(cx, endY),
            SwipeConfig(durationMeanMs = gestureDurationMs.toDouble())
        )
    }
    var result = performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    if (!result) { // 如果第一次滚动失败
        delay(1000 + HumanTouchEngine.randomDelayMs(50,50))
        result = performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) // 再次尝试滚动
    }
    return result // 返回最终滚动是否成功的状态
}
