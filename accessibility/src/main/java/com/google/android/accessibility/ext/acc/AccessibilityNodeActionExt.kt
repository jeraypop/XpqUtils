package com.google.android.accessibility.ext.acc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.copyToClipboard
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.delay

/**
 * 点击事件
 */
fun AccessibilityNodeInfo?.click(): Boolean {
    this ?: return false
    //===
    val nodeBounds = Rect().apply(this::getBoundsInScreen)
    // 确保边界值非负
    val x = Math.max(0, nodeBounds.centerX()).toFloat()
    val y = Math.max(0, nodeBounds.centerY()).toFloat()
    //点击轨迹提示
    accessibilityService?.let { KeyguardUnLock.showClickIndicator(it, x.toInt(), y.toInt()) }
    //===
    return if (isClickable) {
        performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } else {
        parent?.click() == true
    }
}

/**
 * 长按事件
 */
fun AccessibilityNodeInfo.longClick(): Boolean {
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
    return if (isClickable) {
        performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    } else {
        parent.longClick()
    }
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
    val arguments = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
    }
    return performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
}

fun AccessibilityNodeInfo.inputTextPaste(byClipboard: Boolean = false,input: String): Boolean {
    //===
    if (KeyguardUnLock.getShowClickIndicator()){
        val nodeBounds = Rect().apply(this::getBoundsInScreen)
        // 确保边界值非负
        val x = Math.max(0, nodeBounds.centerX()).toFloat()
        val y = Math.max(0, nodeBounds.centerY()).toFloat()
        //点击轨迹提示
        accessibilityService?.let { KeyguardUnLock.showClickIndicator(it, x.toInt(), y.toInt()) }

    }
    val bundle = Bundle()
    //1如果是已经粘贴过,或者edittext有内容,则先清空
    bundle.apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
    }
    performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    //复制到系统剪贴板 byClipboard=false时,不需要,但为了统一能复制到系统剪贴板
    copyToClipboard(text = input)
    val b = if (byClipboard){
        //将输入焦点设置到指定的辅助功能节点上
        performAction(AccessibilityNodeInfo.FOCUS_INPUT)
        //将系统剪贴板中的内容粘贴到该节点
        performAction(AccessibilityNodeInfo.ACTION_PASTE) //粘贴
    }else{
        //复制到bundle
        bundle.apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
        }
        //将bundle的内容粘贴到该节点
        performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }
    //===
    return b
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



    val arguments = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
    }
    return performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
}

/**
 * 向下滚动
 */
fun AccessibilityNodeInfo.scrollBackward() : Boolean =
    performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

/**
 * 向上滚动
 */
suspend fun AccessibilityNodeInfo.scrollForward(): Boolean {
    var result = performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    if (!result) { // 如果第一次滚动失败
        delay(1000)
        result = performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) // 再次尝试滚动
    }
    return result // 返回最终滚动是否成功的状态
}
