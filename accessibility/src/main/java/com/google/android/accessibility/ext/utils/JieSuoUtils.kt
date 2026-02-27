package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.KeyguardUnLock.findAndPerformClickNodeInfo
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getLockViewID
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getScreenSize
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getZQSuccess
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.NumberPickerDialog.hasRoot
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

    @JvmStatic
    @JvmOverloads
    fun showDialogZuobiao(groupKey: String = MMKVConst.KEY_SCREEN_LOCK_POINTS) {
        if (accessibilityService == null){
            AliveUtils.toast(msg = "无障碍服务未开启")
            return
        }
        val service = accessibilityService ?: return
        val windowManager =
            service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

        val(screenWidth, screenHeight) = getScreenSize()
        val inflater = LayoutInflater.from(service)
        // ==============================
        // 控制面板
        // ==============================

        val controlView = inflater.inflate(R.layout.lockposition, null)

        val tvPositionInfo = controlView.findViewById<TextView>(R.id.tv_position_info)
        val btAdd = controlView.findViewById<Button>(R.id.button_add)
        val btSave = controlView.findViewById<Button>(R.id.button_save)
        val btQuit = controlView.findViewById<Button>(R.id.button_quit)

        val controlParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSPARENT
            gravity = Gravity.START or Gravity.TOP
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = screenWidth
            height = screenHeight / 5
            x = 0
            y = screenHeight - height
            alpha = 0.95f
        }

        windowManager.addView(controlView, controlParams)

        // ==============================
        // 控制面板拖动
        // ==============================

        controlView.setOnTouchListener(object : View.OnTouchListener {

            var lastX = 0f
            var lastY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                    }

                    MotionEvent.ACTION_MOVE -> {
                        controlParams.x += (event.rawX - lastX).toInt()
                        controlParams.y += (event.rawY - lastY).toInt()

                        lastX = event.rawX
                        lastY = event.rawY

                        windowManager.updateViewLayout(controlView, controlParams)
                    }
                }
                return true
            }
        })

        // ==============================
        // 数据集合
        // ==============================

        val targetViews = mutableListOf<View>()
        val targetParamsList = mutableListOf<WindowManager.LayoutParams>()
        val positionList = mutableListOf<Pair<Int, Int>>()

        val targetSize = screenWidth / 6

        // ==============================
        // 更新文本
        // ==============================

        fun updatePositionText() {
            tvPositionInfo.text = buildString {
                positionList.forEachIndexed { i, p ->
                    append("点${i + 1}: X=${p.first}  Y=${p.second}\n")
                }
            }
        }

        // ==============================
        // 刷新编号
        // ==============================

        fun refreshAllLabels() {
            targetViews.forEachIndexed { index, view ->
                val container = view as FrameLayout
                val label = container.getChildAt(1) as TextView
                label.text = (index + 1).toString()
            }
        }

        // ==============================
        // 添加准星
        // ==============================

        fun addTarget() {

            val container = FrameLayout(service)

            val targetParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSPARENT
                gravity = Gravity.START or Gravity.TOP
                flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

                width = targetSize
                height = targetSize

                x = screenWidth / 2 - targetSize / 2
                y = screenHeight / 2 - targetSize / 2
            }

            val imageTarget = ImageView(service).apply {
                setImageResource(R.drawable.ic_targetxpq)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            val labelSize = targetSize / 3

            val tvLabel = TextView(service).apply {

                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER

                layoutParams = FrameLayout.LayoutParams(
                    labelSize,
                    labelSize
                ).apply {
                    gravity = Gravity.END or Gravity.TOP
                }

                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.RED)
                }
            }

            container.addView(imageTarget)
            container.addView(tvLabel)

            targetViews.add(container)
            targetParamsList.add(targetParams)
            positionList.add(0 to 0)

            // ==============================
            // 拖动逻辑
            // ==============================
            var isDragging = false  // ✅ 放这里，闭包捕获
            container.setOnTouchListener(object : View.OnTouchListener {

                var lastX = 0f
                var lastY = 0f
                val half = targetSize / 2


                override fun onTouch(v: View, event: MotionEvent): Boolean {

                    when (event.action) {

                        MotionEvent.ACTION_DOWN -> {
                            lastX = event.rawX
                            lastY = event.rawY
                            isDragging = false
                        }

                        MotionEvent.ACTION_MOVE -> {

                            val dx = event.rawX - lastX
                            val dy = event.rawY - lastY

                            // 如果移动超过阈值，就认为是拖动
                            if (!isDragging && (dx * dx + dy * dy) > 25) { // 5px 阈值
                                isDragging = true
                            }

                            if (isDragging) {
                                targetParams.x += dx.toInt()
                                targetParams.y += dy.toInt()
                                windowManager.updateViewLayout(container, targetParams)

                                val index = targetViews.indexOf(container)
                                val centerX = targetParams.x + half
                                val centerY = targetParams.y + half
                                positionList[index] = centerX to centerY
                                updatePositionText()
                            }

                            lastX = event.rawX
                            lastY = event.rawY
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            // 拖动结束
                        }
                    }
                    // 返回 false 让长按仍然可触发，但拖动会阻止误触发
                    return false
                }
            })

            // ==============================
            // 长按删除
            // ==============================

            container.setOnLongClickListener {
                if (isDragging) return@setOnLongClickListener false // 拖动过程中不要删除
                val index = targetViews.indexOf(container)

                try {
                    windowManager.removeViewImmediate(container)
                } catch (_: Exception) {}

                targetViews.removeAt(index)
                targetParamsList.removeAt(index)
                positionList.removeAt(index)

                refreshAllLabels()
                updatePositionText()

                true
            }

            windowManager.addView(container, targetParams)

            refreshAllLabels()
            updatePositionText()
        }

        // ==============================
        // 按钮事件
        // ==============================

        btAdd.setOnClickListener {
            addTarget()
        }

        btSave.setOnClickListener {
            saveLockPoints(groupKey, positionList)
        }

        btQuit.setOnClickListener {

            try {
                windowManager.removeViewImmediate(controlView)
            } catch (_: Exception) {}

            targetViews.forEach {
                try {
                    windowManager.removeViewImmediate(it)
                } catch (_: Exception) {}
            }
        }
    }

    fun saveLockPoints(groupKey: String = MMKVConst.KEY_SCREEN_LOCK_POINTS, positionList: List<Pair<Int, Int>>) {

        if (positionList.isEmpty()) return

        val set = mutableSetOf<String>()

        positionList.forEachIndexed { index, pair ->
            val value = "$index:${pair.first},${pair.second}"
            set.add(value)
        }

        MMKVUtil.put(groupKey, set)
        AliveUtils.toast(msg = "已覆盖保存 ${positionList.size} 个坐标")
    }
    @JvmStatic
    @JvmOverloads
    fun getSavedLockPoints(groupKey: String = MMKVConst.KEY_SCREEN_LOCK_POINTS): List<Point> {

        val set: Set<String> = MMKVUtil.get(groupKey, mutableSetOf())

        return set.mapNotNull { item ->
            try {
                val parts = item.split(":")
                val index = parts[0].toInt()
                val xy = parts[1].split(",")
                val x = xy[0].toFloat()
                val y = xy[1].toFloat()
                index to Point(x, y)
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.first } // 按 index 排序
            .map { it.second }      // 返回 Point 列表
    }

    /**
     * 获取多个编号坐标
     * @param numbers 编号列表（从 1 开始）
     * @return Map<编号, LockPoint>，不存在的编号不会出现在返回值里
     */
    @JvmOverloads
    @JvmStatic
    fun getLockPointsByNumbers(numbers: List<Int>,groupKey: String = MMKVConst.KEY_SCREEN_LOCK_POINTS,): Map<Int, Point> {

        if (numbers.isEmpty()) return emptyMap()

        val numberSet = numbers.toSet()  // 去重
        val set: Set<String> = MMKVUtil.get(groupKey, mutableSetOf())

        val map = set.mapNotNull { item ->
            try {
                val parts = item.split(":")
                val index = parts[0].toInt() + 1  // index 0 对应编号1
                if (index !in numberSet) return@mapNotNull null

                val xy = parts[1].split(",")
                val x = xy[0].toFloat()
                val y = xy[1].toFloat()
                index to Point(x, y)
            } catch (e: Exception) {
                null
            }
        }.toMap()

        return map
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
                //return LockReadState.NOT_LOCK_SCREEN
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
    fun fallbackAppLock(myDigit: String = "123456",
                         hasNode: Boolean = false,
                         context: Context = appContext): Boolean {
        var    isSuc = false
        //1.获取设备的宽和高
        val screenSize = getScreenSize(appContext)
        val w = screenSize.first
        val h = screenSize.second
        if (!hasNode){
            //获取前3个保存的锁屏密码界面数字点的集合一般是 1 5 9这3个点的集合
            val numbersToGet = listOf(1, 2, 3)
            val pointsMap = getLockPointsByNumbers(numbersToGet,MMKVConst.KEY_APP_LOCK_POINTS)

            pointsMap.forEach { (number, point) ->
                Log.e("编号: ", "$number -> X=${point.x}, Y=${point.y}")
            }

            // 单独获取编号123的坐标
            val point1 = pointsMap[1]
            val point2 = pointsMap[2]
            val point3 = pointsMap[3]

            val p1 = Point(point1?.x ?: 297f, point1?.y ?: 1299f)
            val p5 = Point(point2?.x ?: 628f, point2?.y ?: 1597f)
            val p9 = Point(point3?.x ?: 957f, point3?.y ?: 1902f)

            //宽 1256  高 2808
            //val p1 = Point(297f, 1299f)
            //val p5 = Point(628f, 1597f)
            //val p9 = Point(957f, 1902f)
            //根据 1 5 9 这3个点的坐标 初始化 锁屏密码界面的所有数字点的集合
            init(p1, p5, p9)
        }
        sendLog("准备遍历并输入保存的应用锁密码= "+myDigit)
        // 在合法范围内操作
        var trueCount = 0
        var falseCount = 0
        for (i in myDigit.indices) {
            val dig = myDigit[i].toString()
            //=
            var inputSuccess = false
            if (hasNode){
                val node = findDigitNode(digit = dig)
                inputSuccess = KeyguardUnLock.xpqclickNode(isMoNi = true, nodeInfo = node)
            }else{
                val (x, y) = if (false){
                    getDigitPointInt(dig.toInt(), w, h)
                }
                else{
                    getPointInt(dig.toInt())
                }
                //===========================
                inputSuccess = StableGestureClicker.click(x = x, y = y)
                //=
            }


            if (!inputSuccess) {
                falseCount++
                sendLog("自动输入第 ${i + 1} 位应用锁密码, $dig 失败")
            } else {
                trueCount++
                sendLog("自动输入第 ${i + 1} 位应用锁密码, $dig 成功")
            }
            SystemClock.sleep(200)
        }
        sendLog("完成输入应用锁密码: 成功次数=$trueCount, 失败次数=$falseCount")
        if (falseCount == 0){
            isSuc = true
            sendLog("所有应用锁密码输入成功")
        }else{
            isSuc = false
            if (trueCount == 0){
                sendLog("所有应用锁密码输入失败")
            }else{
                sendLog("部分应用锁密码输入失败")
            }

        }
        return isSuc
        //=================================

    }

    @JvmStatic
    @JvmOverloads
    fun fallbackStrategy(myDigit: String = "123456",
                         hasNode: Boolean = false,
                         context: Context = appContext): Boolean {
        var    isSuc = false
        //1.获取设备的宽和高
        val screenSize = getScreenSize(appContext)
        val w = screenSize.first
        val h = screenSize.second
        if (!hasNode){
            //获取前3个保存的锁屏密码界面数字点的集合一般是 1 5 9这3个点的集合
            val numbersToGet = listOf(1, 2, 3)
            val pointsMap = getLockPointsByNumbers(numbersToGet)

            pointsMap.forEach { (number, point) ->
                Log.e("编号: ", "$number -> X=${point.x}, Y=${point.y}")
            }

            // 单独获取编号123的坐标
            val point1 = pointsMap[1]
            val point2 = pointsMap[2]
            val point3 = pointsMap[3]

            val p1 = Point(point1?.x ?: 297f, point1?.y ?: 1299f)
            val p5 = Point(point2?.x ?: 628f, point2?.y ?: 1597f)
            val p9 = Point(point3?.x ?: 957f, point3?.y ?: 1902f)

            //宽 1256  高 2808
            //val p1 = Point(297f, 1299f)
            //val p5 = Point(628f, 1597f)
            //val p9 = Point(957f, 1902f)
            //根据 1 5 9 这3个点的坐标 初始化 锁屏密码界面的所有数字点的集合
            init(p1, p5, p9)
        }
        sendLog("准备遍历并输入保存的密码= "+myDigit)
        // 在合法范围内操作
        var trueCount = 0
        var falseCount = 0
        for (i in myDigit.indices) {
            val dig = myDigit[i].toString()
            //=
            var inputSuccess = false
            if (hasNode){
                val node = findDigitNode(digit = dig)
                inputSuccess = KeyguardUnLock.xpqclickNode(isMoNi = true, nodeInfo = node)
            }else{
                val (x, y) = if (false){
                    getDigitPointInt(dig.toInt(), w, h)
                }
                else{
                    getPointInt(dig.toInt())
                }
                //===========================
                inputSuccess = StableGestureClicker.click(x = x, y = y)
                //=
            }


            if (!inputSuccess) {
                falseCount++
                sendLog("自动输入第 ${i + 1} 位密码, $dig 失败")
            } else {
                trueCount++
                sendLog("自动输入第 ${i + 1} 位密码, $dig 成功")
            }
            SystemClock.sleep(200)
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

    @JvmStatic
    @JvmOverloads
    fun handleLockScreen(
        myDigit: String = "123456",
        context: Context = appContext,
        root: AccessibilityNodeInfo? = accessibilityService?.rootInActiveWindow
    ): Boolean {
        val (w,h) = getScreenSize()
        var    isSuc = false
        //if (!isOnLockScreen(context)) return  isSuc
        val brand = Build.BRAND.uppercase(Locale.getDefault()) //获取设备品牌
        fun getTips(): String {
            val b = getSavedLockPoints().isNullOrEmpty()
            val s = if (b) "请先获取锁屏输入密码界面的数字(1,5,9的坐标)" else ""
            return s
        }

        when (checkLockscreenReadable(context, root)) {

            LockReadState.NOT_LOCK_SCREEN -> {
                // 不处理
                hasRoot = true
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT, true)
                sendLog("当前界面不是锁屏界面")
                return  isSuc
            }

            LockReadState.ROOT_NULL -> {
                hasRoot = false
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT, false)
                val s = getTips()
                sendLog("设备品牌: "+brand+" 系统阻止读取锁屏节点,$s,尝试盲点(如果还是无法解锁,建议取消锁屏密码,注重隐私的话,可以加应用锁)")
                return  fallbackStrategy(myDigit)
            }

            LockReadState.NO_CHILDREN -> {
                hasRoot = false
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT, false)
                val s = getTips()
                sendLog("设备品牌: "+brand+" 锁屏被隔离层包裹,$s,尝试盲点(如果还是无法解锁,建议取消锁屏密码,注重隐私的话,可以加应用锁)")
                return  fallbackStrategy(myDigit)
            }

            LockReadState.STRUCTURE_ONLY -> {
                hasRoot = false
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT, false)
                val s = getTips()
                sendLog("设备品牌: "+brand+" 只能读取结构，不能读取内容,$s,尝试盲点(如果还是无法解锁,建议取消锁屏密码,注重隐私的话,可以加应用锁)")
                return  fallbackStrategy(myDigit)
            }

            LockReadState.FULLY_READABLE -> {
                hasRoot = true
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT, true)
                sendLog("设备品牌: "+brand+" 允许读取锁屏节点")
                return fallbackStrategy(myDigit,true)
            }
        }
    }

    @JvmOverloads
    @JvmStatic
    fun inputAppLockPassword(access_Service: AccessibilityService? = accessibilityService, password: String= KeyguardUnLock.getAppPassWord()): Boolean {
        if (!KeyguardUnLock.getAppLock() || TextUtils.isEmpty(KeyguardUnLock.getAppPassWord())){
            return  false
        }else{
            SystemClock.sleep(2000)
        }
        var    isSuc = false
        if (access_Service == null) {
            sendLog("无障碍服务未开启!")
            return isSuc
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            sendLog("系统版本小于7.0")
            return isSuc
        }
        //val password = MMKVUtil.get(MMKVConst.KEY_LOCK_SCREEN_PASSWORD, "")
        if (TextUtils.isEmpty(password)) {
            sendLog("应用锁密码为空,请先设置密码")
            return isSuc
        }
        //===
        val brand = Build.BRAND.uppercase(Locale.getDefault()) //获取设备品牌
        fun getTips(): String {
            val b = getSavedLockPoints(MMKVConst.KEY_APP_LOCK_POINTS).isNullOrEmpty()
            val s = if (b) "请先获取应用锁输入密码界面的数字(1,5,9的坐标)" else ""
            return s
        }

        when (checkLockscreenReadable(root = access_Service.rootInActiveWindow)) {

            LockReadState.NOT_LOCK_SCREEN -> {
                // 不处理
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT_APPLOCK, true)
                sendLog("当前界面不是锁屏界面")
                return  fallbackAppLock(password)
            }

            LockReadState.ROOT_NULL -> {
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT_APPLOCK, false)
                val s = getTips()
                sendLog("设备品牌: "+brand+" 系统阻止读取应用锁节点,$s,尝试盲点(如果还是无法解锁,建议取消应用锁)")
                return  fallbackAppLock(password)
            }

            LockReadState.NO_CHILDREN -> {
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT_APPLOCK, false)
                val s = getTips()
                sendLog("设备品牌: "+brand+" 应用锁被隔离层包裹,$s,尝试盲点(如果还是无法解锁,建议取消应用锁)")
                return  fallbackAppLock(password)
            }

            LockReadState.STRUCTURE_ONLY -> {
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT_APPLOCK, false)
                val s = getTips()
                sendLog("设备品牌: "+brand+" 只能读取结构，不能读取内容,$s,尝试盲点(如果还是无法解锁,建议取消应用锁)")
                return  fallbackAppLock(password)
            }

            LockReadState.FULLY_READABLE -> {
                MMKVUtil.put(MMKVConst.KEY_HAS_ROOT_APPLOCK, true)
                sendLog("设备品牌: "+brand+" 允许读取应用锁节点")
                return fallbackAppLock(password,true)
            }
        }
        //===
        return isSuc
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