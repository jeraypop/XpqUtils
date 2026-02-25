package com.google.android.accessibility.ext.utils

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.ext.CoroutineWrapper
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getScreenSize
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getZQSuccess
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.KeyguardUnLock.showClickIndicator
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
    fun fallbackStrategy(myDigit: String = "123456",
                         context: Context = appContext): Boolean {
        var    isSuc = false
        //1.获取设备的宽和高
        val screenSize = getScreenSize(appContext)
        val w = screenSize.first
        val h = screenSize.second

        sendLog("准备遍历并输入保存的密码= "+myDigit)
        // 在合法范围内操作
        var trueCount = 0
        var falseCount = 0
        for (i in myDigit.indices) {
            val dig = myDigit[i].toString()
            //=
            //sendLog("强制 Gesture 点击 (${centerX}, ${centerY})")
            val (x, y) = if (true){
                getDigitPointInt(dig.toInt(), w, h)
            }else{
                //宽 1256  高 2808
                val p1 = Point(297f, 1299f)
                val p5 = Point(628f, 1597f)
                val p9 = Point(957f, 1902f)
                init(p1, p5, p9)
                getPointInt(dig.toInt())
            }
            //===========================
            val clicked = StableGestureClicker.click(
                accessibilityService!!,
                x,
                y
            )

            if (clicked) {
                showClickIndicator(
                    accessibilityService!!,
                    x,
                    y
                )
            }
            //=
            if (!clicked) {
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
        return isSuc
        //=================================

    }
    fun getDigitPointOLD(digit: Int, w: Int, h: Int): Pair<Int, Int> {

        val startY = (h * 0.45f).toInt()
        val cellW = w / 3
        val cellH = (h - startY) / 4

        val index = if (digit == 0) 10 else digit - 1

        val row = index / 3
        val col = index % 3

        val x = col * cellW + cellW / 2
        val y = startY + row * cellH + cellH / 2

        return Pair(x, y)
    }


    @JvmStatic
    @JvmOverloads
    fun handleLockScreen(
        myDigit: String = "123456",
        context: Context = appContext,
        root: AccessibilityNodeInfo? = accessibilityService?.rootInActiveWindow
    ): Boolean {
        val (w,h) = getScreenSize()
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
                sendLog("设备品牌: "+brand+" 系统阻止读取锁屏节点,尝试盲点")
                return  fallbackStrategy(myDigit)
            }

            LockReadState.NO_CHILDREN -> {
                sendLog("设备品牌: "+brand+" 锁屏被隔离层包裹")
                return  fallbackStrategy(myDigit)
            }

            LockReadState.STRUCTURE_ONLY -> {
                sendLog("设备品牌: "+brand+" 只能读取结构，不能读取内容")
                return  fallbackStrategy(myDigit)
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

    //=================根据1  5  9 三个点 生成算法
    private var startX = 0f
    private var startY = 0f
    private var cellW = 0f
    private var cellH = 0f
    private var inited = false

    /**
     * 使用 1 / 5 / 9 三点初始化
     */
    fun init(p1: Point, p5: Point, p9: Point) {

        // 基础间距
        val rawCellW = (p9.x - p1.x) / 2f
        val rawCellH = (p9.y - p1.y) / 2f

        // 用 5 做误差修正（更稳）
        cellW = ((p5.x - p1.x) + rawCellW) / 2f
        cellH = ((p5.y - p1.y) + rawCellH) / 2f

        startX = p1.x
        startY = p1.y

        inited = true
    }

    /**
     * 获取数字坐标（返回 Float 精度）
     */
    fun getPoint(digit: Int): Point {

        check(inited) { "LockGrid159 not initialized" }

        val index = if (digit == 0) 10 else digit - 1
        val row = index / 3
        val col = index % 3

        val x = startX + col * cellW
        val y = startY + row * cellH

        return Point(x, y)
    }

    /**
     * 如果你需要直接点击用 Int
     */
    fun getPointInt(digit: Int): Pair<Int, Int> {
        val p = getPoint(digit)
        return Pair(p.x.toInt(), p.y.toInt())
    }

    //=================根据宽和高 完全自动生成算法
    // 横向比例（固定三列）
    private val xRatio = floatArrayOf(
        0.236f,   // 左列
        0.5f,     // 中列
        0.764f    // 右列
    )

    // 纵向比例（四行）
    private val yRatio = floatArrayOf(
        0.463f,   // 第一行
        0.568f,   // 第二行
        0.677f,   // 第三行
        0.785f    // 第四行（0）
    )

    fun getDigitPoint(
        digit: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Point {

        val index = if (digit == 0) 10 else digit - 1

        val row = index / 3
        val col = index % 3

        val x = (screenWidth * xRatio[col])
        val y = (screenHeight * yRatio[row])

        return Point(x, y)
    }
    /**
     * 如果你需要直接点击用 Int
     */
    fun getDigitPointInt(
        digit: Int,
        w: Int,
        h: Int
    ): Pair<Int, Int> {
        val p = getDigitPoint(digit,w,h)
        return Pair(p.x.toInt(), p.y.toInt())
    }
    //=================根据宽和高 完全自动生成算法
    data class Point(val x: Float, val y: Float)


}