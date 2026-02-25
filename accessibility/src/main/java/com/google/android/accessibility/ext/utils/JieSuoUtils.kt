package com.google.android.accessibility.ext.utils

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.ext.CoroutineWrapper
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getZQSuccess
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/2/25  9:26
 * Description:This is JieSuoUtils
 */
object JieSuoUtils {
    enum class LockReadState {
        NOT_LOCK_SCREEN,
        ROOT_NULL,
        NO_CHILDREN,
        STRUCTURE_ONLY,
        FULLY_READABLE
    }
    //锁屏检测
    @JvmStatic
    @JvmOverloads
    fun isOnLockScreen(context: Context = appContext): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    //检测 ROM 是否允许读取锁屏节点
    @JvmStatic
    @JvmOverloads
    fun checkLockscreenReadable(
        context: Context = appContext,
        root: AccessibilityNodeInfo? = accessibilityService?.rootInActiveWindow
    ): LockReadState {

        if (!getZQSuccess()){
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) {
                return LockReadState.NOT_LOCK_SCREEN
            }
        }


        if (root == null) {
            return LockReadState.ROOT_NULL
        }

        if (root.childCount == 0) {
            return LockReadState.NO_CHILDREN
        }

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        var hasText = false

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()

            if (!node.text.isNullOrEmpty() ||
                !node.contentDescription.isNullOrEmpty()
            ) {
                hasText = true
                break
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }

        return if (hasText)
            LockReadState.FULLY_READABLE
        else
            LockReadState.STRUCTURE_ONLY
    }

    // 在允许读取时查找数字节点
    @JvmStatic
    @JvmOverloads
    private fun findDigitNode(
        digit: String = "1",
        root: AccessibilityNodeInfo? = accessibilityService?.rootInActiveWindow
    ): AccessibilityNodeInfo? {

        if (root == null) return null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            //其实就是不额外判断节点是否可点击了  && (node.isClickable || !node.isClickable)
            if (text == digit || desc == digit) {
                node.viewIdResourceName?.toString()
                sendLog("解锁数字节点ID: " + node.viewIdResourceName)
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }

        return null
    }
    @JvmStatic
    @JvmOverloads
    fun fallbackStrategy(context: Context = appContext) {

    }

    @JvmStatic
    @JvmOverloads
    fun handleLockScreen(
        myDigit: String = "123456",
        context: Context = appContext,
        root: AccessibilityNodeInfo? = accessibilityService?.rootInActiveWindow
    ): Boolean {
        var    isSuc = false
        if (!isOnLockScreen(context)) return  isSuc
        val brand = Build.BRAND.uppercase(Locale.getDefault()) //获取设备品牌
        when (checkLockscreenReadable(context, root)) {

            LockReadState.NOT_LOCK_SCREEN -> {
                // 不处理
                sendLog("当前界面不是锁屏界面")
                return  isSuc
            }

            LockReadState.ROOT_NULL -> {
                sendLog("设备品牌: "+brand+" ROM阻止读取锁屏节点")
                fallbackStrategy(context)
                return  isSuc
            }

            LockReadState.NO_CHILDREN -> {
                sendLog("设备品牌: "+brand+" 锁屏被隔离层包裹")
                fallbackStrategy(context)
                return  isSuc
            }

            LockReadState.STRUCTURE_ONLY -> {
                sendLog("设备品牌: "+brand+" 只能读取结构，不能读取内容")
                fallbackStrategy(context)
                return  isSuc
            }

            LockReadState.FULLY_READABLE -> {
                sendLog("设备品牌: "+brand+" 允许读取锁屏节点")
                sendLog("准备遍历并输入保存的密码= "+myDigit)
                // 在合法范围内操作
                var trueCount = 0
                var falseCount = 0
                for (i in myDigit.indices) {
                    val dig = myDigit[i].toString()
                    val inputSuccess = KeyguardUnLock.xpqclickNode(isMoNi = true, nodeInfo = findDigitNode(digit = myDigit[i].toString()))
                    if (!inputSuccess) {
                        falseCount++
                        sendLog("自动输入第 ${i + 1} 位密码, $dig 失败")
                    } else {
                        trueCount++
                        sendLog("自动输入第 ${i + 1} 位密码, $dig 成功")
                    }
                    SystemClock.sleep(100)
                }
                sendLog("完成输入密码: 成功次数=$trueCount, 失败次数=$falseCount")
                if (falseCount == 0){
                    isSuc = true
                    sendLog("所有密码输入成功")
                }else{
                    isSuc = false
                    if (trueCount == 0){
                        sendLog("所有密码输入失败")
                    }else{
                        sendLog("部分密码输入失败")
                    }

                }
                return  isSuc
            }
        }
    }


}