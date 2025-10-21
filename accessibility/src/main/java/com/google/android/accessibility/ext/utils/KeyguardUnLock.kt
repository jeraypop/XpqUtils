package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.IntRange
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.window.ClickIndicatorManager
import com.google.android.accessibility.ext.window.LogWrapper
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/10/15  10:38
 * Description:This is KeyguardUnLock
 */

// 表示设备锁定与安全配置的组合状态
sealed class DeviceLockState {
    // 设备已解锁（可能设置了安全锁，也可能未设置）
    data class Unlocked(val isDeviceSecure: Boolean) : DeviceLockState()

    // 设备当前被锁，但没有安全锁（例如滑动解锁） —— 可直接解除
    object LockedNotSecure : DeviceLockState()

    // 设备当前被锁，并且设置了安全锁（PIN/图案/密码/生物） —— 需要用户验证
    object LockedSecure : DeviceLockState()
}
/**
 * 更丰富的屏幕状态枚举，支持 AOD / Doze 场景。
 */
enum class ScreenState {
    ON,        // 屏幕亮并可交互
    AOD,       // Always-On Display（显示常亮信息） 屏幕“看似关”，但在显示时间通知
    DOZING,    // 正在 Doze（深度省电，通常屏幕黑或仅极少量唤醒）  屏幕完全黑、CPU 降频
    OFF,       // 屏幕关闭（非交互、非 AOD/Doze）
    UNKNOWN    // 无法判断
}
data class DeviceStatus(
    val lockState: DeviceLockState,
    val screenState: ScreenState
)


object KeyguardUnLock {

    /**
     * 返回组合的设备状态：锁定状态 + 更丰富的屏幕状态（支持 AOD / DOZING 判断）
     * 根据 KeyguardManager 的两个 API 判断当前设备状态，返回 DeviceLockState。
     *@param context 任意 Context（使用 applicationContext 更安全）
     *@param byKeyguard 如果 true 使用 isKeyguardLocked 判断“锁”，否则使用 isDeviceLocked 判断（更严格）
     *
     * 使用场景：
     *  - 如果返回 Unlocked(isDeviceSecure = true) 表示设备已解锁，但用户设置了安全锁（已验证）。
     *  - 如果 LockedNotSecure：设备被锁但无安全锁，可直接解除/继续敏感操作。
     *  - 如果 LockedSecure：设备被锁且有安全保护，需要调用 requestDismissKeyguard() 或引导用户验证。
     */
    @JvmOverloads
    @JvmStatic
    fun getDeviceLockState(byKeyguard: Boolean = true): DeviceLockState {
        val km = appContext.getSystemService(KeyguardManager::class.java)
            ?: return DeviceLockState.Unlocked(isDeviceSecure = false) // 保守默认：当无法获取时当作未配置安全锁并解锁

        val deviceSecure = km.isDeviceSecure  // 设备是否配置了 PIN/Pattern/密码/生物 等
        val deviceLocked = if (!byKeyguard) {
            km.isDeviceLocked   // 当前设备是否处于“锁定”状态（需要验证才能访问用户数据）
        } else {
            km.isKeyguardLocked
        }
        return when {
            // 设备没有被锁（可直接使用），无论是否配置安全锁
            !deviceLocked -> DeviceLockState.Unlocked(isDeviceSecure = deviceSecure)

            // 设备被锁并且没有配置安全锁（例如只有滑动解锁） -> 可以自动解除（系统可以直接 dismiss）
            deviceLocked && !deviceSecure -> DeviceLockState.LockedNotSecure

            // 设备被锁且配置了安全锁 -> 需要用户验证
            deviceLocked && deviceSecure -> DeviceLockState.LockedSecure

            else -> DeviceLockState.Unlocked(isDeviceSecure = deviceSecure) // 保守兜底
        }
    }
    @JvmOverloads
    @JvmStatic
    fun getDeviceStatusPlus(context: Context =appContext , byKeyguard: Boolean = true): DeviceStatus {
        val appCtx = context.applicationContext

        val pm = appCtx.getSystemService(PowerManager::class.java)
        val isInteractive = pm?.isInteractive ?: false

        // 初步 raw display state 检测（需要 API 23+ 来支持 DOZE display states）
        var rawScreenState = ScreenState.UNKNOWN
        if (isInteractive) {
            rawScreenState = ScreenState.ON
        }
        else {
            // 非交互状态，尝试通过 DisplayManager / PowerManager 辨别 AOD / DOZING / OFF
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 优先使用 Display.state 判断 AOD / DOZE 状态（更直接）
                val dm = appCtx.getSystemService(DisplayManager::class.java)
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                val dispState = display?.state ?: Display.STATE_UNKNOWN

                rawScreenState = when (dispState) {
                    Display.STATE_DOZE -> ScreenState.AOD
                    Display.STATE_DOZE_SUSPEND -> ScreenState.DOZING
                    else -> {
                        // dispState 未指示 doze：再看 PowerManager 的 device idle（Doze 模式）
                        val isDeviceIdle = try {
                            pm?.isDeviceIdleMode ?: false
                        } catch (t: Throwable) {
                            false
                        }

                        if (isDeviceIdle) ScreenState.DOZING else ScreenState.OFF
                    }
                }
            } else {
                // 低版本没有 DOZE display state，退回到简单判断
                rawScreenState = if (isInteractive) ScreenState.ON else ScreenState.OFF
            }
        }

        val km = appCtx.getSystemService(KeyguardManager::class.java)
        if (km == null) {
            // 无法获取 KeyguardManager：保守认为未配置安全锁，返回当前检测到的屏幕状态
            return DeviceStatus(
                lockState = DeviceLockState.Unlocked(isDeviceSecure = false),
                screenState = rawScreenState
            )
        }

        val deviceSecure = km.isDeviceSecure  // 设备是否配置了 PIN/Pattern/密码/生物 等

        val deviceLocked = try {
            if (byKeyguard) km.isKeyguardLocked else km.isDeviceLocked
        } catch (e: Throwable) {
            // 厂商兼容问题：若调用出错，兜底认为未锁
            false
        }

        // 实效屏幕状态策略：若设备未锁定，则视为 ON（避免短暂竞态导致的误判）
        val effectiveScreenState = if (!deviceLocked) {
            ScreenState.ON
        } else {
            rawScreenState
        }

        val lockState = when {
            // 设备没有被锁（可直接使用），无论是否配置安全锁
            !deviceLocked -> DeviceLockState.Unlocked(isDeviceSecure = deviceSecure)

            // 设备被锁并且没有配置安全锁（例如只有滑动解锁） -> 可以自动解除（系统可以直接 dismiss）
            deviceLocked && !deviceSecure -> DeviceLockState.LockedNotSecure

            // 设备被锁且配置了安全锁 -> 需要用户验证
            deviceLocked && deviceSecure -> DeviceLockState.LockedSecure

            else -> DeviceLockState.Unlocked(isDeviceSecure = deviceSecure) // 保守兜底
        }

        return DeviceStatus(lockState = lockState, screenState = effectiveScreenState)
    }


    @JvmStatic
    fun getLockID(): String {
        val brand = Build.BRAND.uppercase(Locale.getDefault()) //获取设备品牌
        Log.e("解锁", "手机品牌 = " + brand);
        var id = "com.android.systemui:id/"
        when (brand) {
            "XIAOMI", "MIUI" -> {
                id = id + "digit_text"
            }

            "OPPO" -> {
                id = id + "oppo_digit_text"
            }

            "IQOO" -> {
                id = id + "iqoo_digit_text"
            }

            "VIVO" -> {
                id = id + "vivo_digit_text"
            }

            else -> {
                // 默认
                id = id + "digit_text"
            }
        }
        return id
    }

    @JvmStatic
    fun getLockViewID(nodeInfo: AccessibilityNodeInfo?): String {
        if (nodeInfo == null) {
            return getLockID()
        }

        //return ""
        return findLockView(nodeInfo) ?: getLockID()
    }

    private fun findLockView(nodeInfo: AccessibilityNodeInfo): String? {
        val className = nodeInfo.className?.toString() ?: ""
        val className_lower = className.lowercase()
        val viewIdName = nodeInfo.viewIdResourceName ?: ""
        val viewIdName_lower = viewIdName.lowercase()

        // 检查是否为目标TextView节点
        if (
            (
                    className_lower.contains("text")
                            ||className_lower.contains("button")
                            ||className_lower.contains("chip")
                    )&&
            viewIdName_lower.contains("id")&&
            (
                    viewIdName_lower.contains("digittext")
                            ||viewIdName_lower.contains("digit_text")
                    )
        ) {
            sendLog("本次查询解锁界面节点id= "+viewIdName)
            return viewIdName
        }

        // 递归遍历子节点
        for (i in 0 until nodeInfo.childCount) {
            nodeInfo.getChild(i)?.let { childNode ->
                val result = findLockView(childNode)
                if (result != null) {
                    return result
                }
            }
        }

        return null
    }
    @JvmOverloads
    @JvmStatic
    fun screenIsOn(context: Context = appContext): Boolean {
        var isScreenOn = false
        //是否需要亮屏唤醒屏幕
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            //屏幕黑屏需要唤醒
            isScreenOn = false
        }else{
            //屏幕是亮着的 不需要唤醒
            isScreenOn = true
        }
        return isScreenOn
    }
    @JvmOverloads
    @JvmStatic
    fun wakeScreenOn(context: Context = appContext): Boolean {
        var isScreenOn = false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            val wl = powerManager.newWakeLock(
                PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_DIM_WAKE_LOCK,
                context.packageName
            )
            wl.acquire(60 * 1000L /*1 minutes*/)
            wl.release()
            isScreenOn = true
        } catch (e: Exception) {
            isScreenOn = false
            //再试一次
            val wl = powerManager.newWakeLock(
                PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_DIM_WAKE_LOCK,
                context.packageName
            )
            wl.acquire(60 * 1000L /*1 minutes*/)
            wl.release()
        }
        return isScreenOn
    }

    @JvmOverloads
    @JvmStatic
    fun keyguardIsOn(context: Context = appContext): Boolean {
        var isKeyguardOn = false
        val mKeyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (mKeyguardManager.isKeyguardLocked){
            //键盘锁定,需要解锁
            isKeyguardOn = false
        }else{
            //键盘没锁定,不需要解锁
            isKeyguardOn = true
        }
        return isKeyguardOn
    }
    /**
     * 其它都一样,只有 锁屏上弹出“闹钟界面”  这个时候 再进行判定
     * true（设备仍锁定isDeviceLocked）	false（Keyguard 被临时隐藏）
     * */
    @JvmOverloads
    @JvmStatic
    fun deviceIsOn(context: Context = appContext): Boolean {
        var isDeviceOn = false
        val mKeyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (mKeyguardManager.isDeviceLocked){
            //设备锁定,需要解锁
            isDeviceOn = false
        }else{
            //设备没锁定,不需要解锁
            isDeviceOn = true
        }
        return isDeviceOn
    }
    @JvmOverloads
    @JvmStatic
    fun unlockScreen(access_Service: AccessibilityService? = accessibilityService, password: String=""): Boolean {
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
            sendLog("解锁密码为空,请先输入密码")
            return isSuc
        }
        //1.获取设备的宽和高
        val screenSize = getScreenSize(appContext)
        val screenWidth = screenSize.first
        val screenHeight = screenSize.second

        sendLog("设备的宽度= "+screenWidth+", 高度= "+screenHeight)
        Log.e("解锁", "screenHeight" + screenHeight)
        Log.e("解锁", "screenWidth" + screenWidth)
        val y = screenHeight / 12 * 9f
        Log.e("解锁", "第1.1步,上划屏幕呼出输入解锁密码界面")
        sendLog("第1.1步,上划屏幕呼出输入解锁密码界面")
        //===============
        //1.向上滑动进入密码解锁界面
        val path = Path()
        path.moveTo(screenWidth / 8f, y)   //滑动起点
        path.lineTo(screenWidth / 8f, y - 800f)//滑动终点
        fun jiesuoRun(digitId: String) {
            //====================

            if (TextUtils.equals("0",password)|| (password.length>0 && password.length<4)){
                //return
            }
            Log.e("解锁", "第2步:获得解锁界面节点id= "+digitId)
            sendLog("第2步:获得解锁界面节点id= "+digitId)
            //====================

            //3.模拟锁屏密码输入完成解锁
            Log.e("解锁", "第3步,准备遍历并输入保存的密码= "+password)
            sendLog("第3步,准备遍历并输入保存的密码= "+password)
            var num=1
            fun inputMiMa(s: Char) =
                findAndPerformClickNodeInfo(
                    access_Service!!,
                    digitId,
                    s.toString(),
                    s.toString()
                )

            var trueCount = 0
            var falseCount = 0
            for (s in password!!) {
                val inputSuccess = inputMiMa(s)
                if (!inputSuccess) {
                    falseCount++
                    val i = num++
                    Log.e("解锁", "输入密码 "+s+" 失败,"+" 第 "+ i+" 位密码")
                    sendLog("自动输入第 "+i+" 位密码, "+ s +" 失败")
                } else {
                    trueCount++
                    val i = num++
                    Log.e("解锁", "输入密码 "+s+" 成功,"+" 第 "+ i+" 位密码")
                    sendLog("自动输入第 "+i+" 位密码, "+ s +" 成功")
                }
            }
            Log.e("解锁", "第3.2步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")
            sendLog("第3.2步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")

            if (falseCount == 0){
                isSuc = true
                sendLog("第3.3步,所有密码输入成功")

            }else{
                isSuc = false
                sendLog("第3.3步,所有密码输入失败")

            }

            return
        }


        //===================
        move(
            access_Service!!,
            path,
            500, 500,
            object : MoveCallback {
                override fun onSuccess() {
                    sendLog("第1.2步,手势上划成功,然后开始输入密码解锁")
                    //睡眠一下 等待 解锁界面加载出来
                    SystemClock.sleep(1000)
                    jiesuoRun(getLockViewID(access_Service.rootInActiveWindow))
                    //===
                }

                //==============================================================
                override fun onError() {
                    sendLog("第1.2步,手势上划失败")
                }
            })
        return isSuc
    }

    @JvmOverloads
    @JvmStatic
    fun unlockScreenNew(access_Service: AccessibilityService? = accessibilityService, password: String=""): Boolean {
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
            sendLog("解锁密码为空,请先输入密码")
            return isSuc
        }
        //===============
        fun jiesuoRun(digitId: String) {
            //====================
            Log.e("解锁", "第1步:获得解锁界面节点id= "+digitId)
            sendLog("第1步:获得解锁界面节点id= "+digitId)
            //====================
            //3.模拟锁屏密码输入完成解锁
            Log.e("解锁", "第2步,准备遍历并输入保存的密码= "+password)
            sendLog("第2.1步,准备遍历并输入保存的密码= "+password)
            var num=1
            fun inputMiMa(s: Char) =
                findAndPerformClickNodeInfo(
                    access_Service!!,
                    digitId,
                    s.toString(),
                    s.toString()
                )
            var trueCount = 0
            var falseCount = 0
            for (s in password!!) {
                val inputSuccess = inputMiMa(s)
                if (!inputSuccess) {
                    falseCount++
                    val i = num++
                    sendLog("自动输入第 "+i+" 位密码, "+ s +" 失败")
                } else {
                    trueCount++
                    val i = num++
                    sendLog("自动输入第 "+i+" 位密码, "+ s +" 成功")
                }
            }
            sendLog("第2.2步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")

            if (falseCount == 0){
                isSuc = true
                sendLog("第2.3步,所有密码输入成功")
            }else{
                isSuc = false
                sendLog("第2.3步,所有密码输入失败")
            }

        }


        //===================
        jiesuoRun(getLockViewID(access_Service.rootInActiveWindow))
        return isSuc
    }

    /**
     * (0,0) ─────────────────────────► X（向右增加）
     *   │
     *   │
     *   ▼
     *   Y（向下增加）
     *
     *
     * */
    @JvmOverloads
    @JvmStatic
    fun unlockMove(access_Service: AccessibilityService? = accessibilityService, start: Long=500L, duration: Long=500L, password: String=""): Boolean {
        var    isSuc = false
        if (access_Service == null) {
            sendLog("无障碍服务未开启!")
            return isSuc
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            sendLog("系统版本小于7.0")
            return isSuc
        }

        //1.获取设备的宽和高
        val screenSize = getScreenSize(appContext)
        val screenWidth = screenSize.first
        val screenHeight = screenSize.second

        sendLog("设备的宽度= "+screenWidth+", 高度= "+screenHeight)
        Log.e("解锁", "screenHeight" + screenHeight)
        Log.e("解锁", "screenWidth" + screenWidth)
        val y = screenHeight / 12 * 9f
        Log.e("解锁", "第1.1步,上划屏幕呼出输入解锁密码界面")
        sendLog("第1.1步,上划屏幕呼出输入解锁密码界面")
        //===============
        //1.向上滑动进入密码解锁界面
        val path = Path()
        path.moveTo(screenWidth / 8f, y)   //滑动起点
        path.lineTo(screenWidth / 8f, y - 800f)//滑动终点

        //===================
        move(access_Service!!, path, start, duration,
            object : MoveCallback {
                override fun onSuccess() {
                    sendLog("第1.2步,手势上划成功,然后开始输入密码解锁")
                    if (!TextUtils.isEmpty(password)){
                        //睡眠一下 等待 解锁界面加载出来
                        SystemClock.sleep(1000)
                        //===
                        unlockScreenNew(access_Service, password)
                    }

                }

                //==============================================================
                override fun onError() {
                    sendLog("第1.2步,手势上划失败")
                }
            })
        return isSuc
    }

    @JvmStatic
    fun getScreenSize(context: Context): Pair<Int, Int> {
        val displayMetrics = context.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        return Pair(width, height)
    }


    /**
     * 滑动操作
     *
     * @param service   无障碍服务实例
     * @param path      移动路径
     * @param startTime 延时启动时间
     * @param duration  执行持续时间
     */

    @JvmStatic
    fun move(
        service: AccessibilityService,
        path: Path,
        @IntRange(from = 0) startTime: Long,
        @IntRange(from = 0) duration: Long,
        moveCallback: MoveCallback?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (moveCallback == null) {
            service.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(StrokeDescription(path, startTime, duration)).build(),
                null,
                null
            )
        } else {
            service.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(StrokeDescription(path, startTime, duration)).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        moveCallback.onSuccess()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        moveCallback.onError()
                    }
                },
                null
            )
        }
    }


    /**
     * 查找并点击节点
     */

    @JvmStatic
    fun findAndPerformClickNodeInfo(
        service: AccessibilityService,
        id: String,
        text: String,
        contentDescription: String
    ): Boolean {
        return performClickNodeInfo(service,findNodeInfo(service, id, text, contentDescription))
    }

    /**
     * 点击节点
     *
     * @return true表示点击成功
     */
    private val clickScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @JvmStatic
    fun release() {
        clickScope?.cancel()
    }

    @JvmStatic
    fun setUnLockOldOrNew(isNew: Boolean = false) {
        MMKVUtil.put(MMKVConst.UNLOCK_METHOD,isNew)
    }
    @JvmStatic
    fun getUnLockOldOrNew(): Boolean {
       return MMKVUtil.get(MMKVConst.UNLOCK_METHOD,false)
    }

    @JvmStatic
    fun setShowClickIndicator(isShow: Boolean) {
       MMKVUtil.put(MMKVConst.SHOW_DO_GUIJI, isShow)
    }
    @JvmStatic
    fun getShowClickIndicator(): Boolean {
        return MMKVUtil.get(MMKVConst.SHOW_DO_GUIJI, false)
    }

    @JvmStatic
    fun showClickIndicator(service: AccessibilityService, x: Int, y: Int) {
        val showguiji = MMKVUtil.get(MMKVConst.SHOW_DO_GUIJI, false)
        if (showguiji){
            // 在主线程显示指示器
            Handler(Looper.getMainLooper()).post {
                ClickIndicatorManager.show(service, x, y)
            }
        }

    }

    @JvmOverloads
    @JvmStatic
    fun performClickNodeInfo(
        service: AccessibilityService,
        nodeInfo: AccessibilityNodeInfo? ,
        isMoNi: Boolean = true
    ): Boolean {
        if (nodeInfo == null) return false
        if (isMoNi){
            // 模拟真实点击（不依赖 isClickable）
            //只要点击坐标有效，就一定会触发系统的点击事件
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // 模拟真实点击（不依赖 isClickable）
                val rect = Rect()
                nodeInfo.getBoundsInScreen(rect)
                if (rect.centerX() > 0 && rect.centerY() > 0) {
                    sendLog("模拟点击解锁按钮 (${rect.centerX()}, ${rect.centerY()})")
                    // 在主线程显示指示器
                    showClickIndicator(service, rect.centerX(), rect.centerY())
                    // 在后台真正点击（避免阻塞主线程）
                    clickScope.launch {
                        try {
                            moniClick(rect.centerX(), rect.centerY(), service)
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        }
                    }
                    return true
                }
            } else {
                // 旧系统使用 ACTION_CLICK，需要检查 isClickable
                if (nodeInfo.isClickable) {
                    nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }else{
            // 非模拟点击
            try {
                // 1️⃣ 当前节点可点击

                if (nodeInfo.isClickable) {
                    val rect = Rect()
                    nodeInfo.getBoundsInScreen(rect)
                    return  if (rect.centerX() > 0 && rect.centerY() > 0) {
                        sendLog("点击解锁按钮 (${rect.centerX()}, ${rect.centerY()})")
                        // 在主线程显示指示器
                        showClickIndicator(service, rect.centerX(), rect.centerY())
                        // 在后台真正点击（避免阻塞主线程）
                        clickScope.launch {
                            try {
                                nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            } catch (t: Throwable) {
                                false
                            }
                        }
                      true
                    } else {
                        false
                    }
                }

                // 2️⃣ 当前节点不可点击，则尝试父节点
                val parent = nodeInfo.parent
                if (parent != null) {
                    val clicked = performClickNodeInfo(service, parent)

                    // ⚠️ 避免 Android 14+ 因 "recycled object" 崩溃
                    if (Build.VERSION.SDK_INT < 34) {
                        try {
                            parent.recycle()
                        } catch (_: Throwable) {
                        }
                    }

                    return clicked
                }

            } catch (t: Throwable) {
                // 避免部分设备 AccessibilityNodeInfo 异常崩溃

            }
        }

        return false
    }



    /**
     * 查找节点信息
     *
     * @param id                 控件id  eg: com.tencent.mm:id/aqg
     * @param text               控件文本 eg: 打开
     * @param contentDescription 控件描述 eg: 表情
     * @return null表示未找到
     */
    /*    @JvmStatic
        fun findNodeInfo(
            service: AccessibilityService,
            id: String,
            text: String,
            contentDescription: String
        ): AccessibilityNodeInfo? {
            if (TextUtils.isEmpty(id) && TextUtils.isEmpty(text)) {
                return null
            }
            SystemClock.sleep(500)
            val nodeInfo = service.rootInActiveWindow
            if (nodeInfo != null) {
                val list = nodeInfo
                    .findAccessibilityNodeInfosByViewId(id)
                for (n in list) {
                    val nodeInfoText =
                        if (TextUtils.isEmpty(n.text)) "" else n.text
                            .toString()
                    val nodeContentDescription =
                        if (TextUtils.isEmpty(n.contentDescription)) "" else n.contentDescription
                            .toString()
                    if (TextUtils.isEmpty(text)) {
                        if (contentDescription == nodeContentDescription) {
                            return n
                        }
                    } else {
                        if (text == nodeInfoText) {
                            return n
                        }
                    }
                }
            }
            return null
        }*/

    /**
     * 查找节点（优先级：id -> text -> contentDescription）
     *
     * 行为要点：
     * - 如果 idNodes 不为空且有匹配的节点（按 text 或 desc）则直接返回；
     * - 如果 idNodes 不为空但没有任何匹配，则继续做 text 查找；
     * - 如果 textNodes 不为空但没有匹配，则不再做 contentDescription 查找（直接下一次重试）；
     * - 只有在 textNodes 为空时，才做 contentDescription 查找；
     */
    @JvmOverloads
    @JvmStatic
    fun findNodeInfo(
        service: AccessibilityService,
        id: String?,
        text: String?,
        contentDescription: String?,
        maxAttempts: Int = 5,
        baseDelayMillis: Long = 100L
    ): AccessibilityNodeInfo? {
        if (id.isNullOrEmpty() && text.isNullOrEmpty() && contentDescription.isNullOrEmpty()) {
            return null
        }
        SystemClock.sleep(500)
        repeat(maxAttempts) { attempt ->
            try {
                val root = service.rootInActiveWindow
                if (root == null) {
                    // 根节点不可用 -> 等待下一次重试
                    SystemClock.sleep(baseDelayMillis + attempt * baseDelayMillis)
                    return@repeat
                }

                // === Step 1: 根据 id 查找 ===
                var hadIdCandidates = false
                if (!id.isNullOrEmpty()) {
                    val idNodes = try {
                        root.findAccessibilityNodeInfosByViewId(id)
                    } catch (_: Exception) {
                        emptyList<AccessibilityNodeInfo>()
                    }

                    if (idNodes.isNotEmpty()) {
                        hadIdCandidates = true
                        for (node in idNodes) {
                            try {
                                if (!node.isVisibleToUser) continue
                                val nodeText = node.text?.toString() ?: ""
                                val nodeDesc = node.contentDescription?.toString() ?: ""

                                val matchByText = !text.isNullOrEmpty() && text == nodeText
                                val matchByDesc =
                                    !contentDescription.isNullOrEmpty() && contentDescription == nodeDesc

                                if (matchByText || matchByDesc) {
                                    // 找到满足要求的节点，直接返回（结束函数）
                                    sendLog("通过 $id 找到解锁按钮")
                                    return node
                                }
                            } catch (_: Throwable) {
                                // 单个节点异常，继续检查下一个
                                sendLog("单个节点异常，继续检查下一个")
                            }
                        }
                        // idNodes 非空但没有找到匹配 -> 按你的要求，继续进行 text 查找（不要跳过）
                    }else{
                        sendLog("通过 $id 找不到解锁按钮")
                    }
                }else{
                    sendLog("id 为空, 准备通过文字来查找解锁按钮")
                }

                // === Step 2: 根据 text 查找 ===
                var hadTextCandidates = false
                if (!text.isNullOrEmpty()) {
                    val textNodes = try {
                        root.findAccessibilityNodeInfosByText(text)
                    } catch (_: Exception) {
                        emptyList<AccessibilityNodeInfo>()
                    }

                    if (textNodes.isNotEmpty()) {
                        hadTextCandidates = true
                        val foundByText = textNodes.firstOrNull {
                            try {
                                val viewIdName = it.viewIdResourceName ?: ""
                                val viewIdName_lower = viewIdName.lowercase()
                                it.isVisibleToUser && (it.text?.toString() == text) && ( viewIdName_lower.contains("digit"))
                            } catch (_: Throwable) {
                                false
                            }
                        }
                        if (foundByText != null) {
                            // 找到精确匹配的 text 节点，返回（结束函数）
                            sendLog("通过 文字 $text 找到解锁按钮")
                            return foundByText
                        } else {
                            // textNodes 非空但没有匹配 -> **根据你的规则，不再做 contentDescription 查找**
                            // 直接等待并进行下一次重试
                            SystemClock.sleep(baseDelayMillis + attempt * baseDelayMillis)
                            return@repeat
                        }
                    }else{
                        sendLog("通过 文字 $text 找不到解锁按钮")
                    }
                } else{
                    sendLog("文字 为空, 准备通过描述来查找解锁按钮")
                }

                // === Step 3: 根据 contentDescription 查找（仅当 textNodes 为空时才做） ===
                if (!contentDescription.isNullOrEmpty()) {
                    // 只有在 textNodes 为空时才查找 contentDescription
                    if (!hadTextCandidates) {
                        val descNode = findNodeByContentDescription(root, contentDescription)
                        if (descNode != null){
                            sendLog("通过 描述 $contentDescription 找到解锁按钮")
                            return descNode
                        }
                    } else {
                        // hadTextCandidates == true 并且没有 text 精确匹配 -> 已在上面返回@repeat（因此这里通常不会到达）
                    }
                }else{
                    sendLog("描述 为空, 找不到解锁按钮")
                }

            } catch (_: Throwable) {
                // 忽略单次异常
            }

            // 重试等待（渐进退避）
            SystemClock.sleep(baseDelayMillis + attempt * baseDelayMillis)
        }

        return null
    }

    /**
     * 辅助：按 contentDescription 逐节点查找（广度优先，带访问上限）
     */
    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, targetDesc: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        val maxVisit = 2000

        while (stack.isNotEmpty() && visited++ < maxVisit) {
            val node = stack.removeFirst()
            try {
                val viewIdName = node.viewIdResourceName ?: ""
                val viewIdName_lower = viewIdName.lowercase()

                val desc = node.contentDescription?.toString()
                if (!desc.isNullOrEmpty() && desc == targetDesc && node.isVisibleToUser && ( viewIdName_lower.contains("digit"))) {
                    return node
                }
                val count = try { node.childCount } catch (_: Throwable) { 0 }
                for (i in 0 until count) {
                    val child = try { node.getChild(i) } catch (_: Throwable) { null }
                    child?.let { stack.add(it) }
                }
            } catch (_: Throwable) {
                // 忽略单节点异常，继续遍历
            }
        }
        return null
    }


    /**
     * 模拟
     * 点击
     */

    @JvmStatic
    fun moniClick(X: Int, Y: Int, service: AccessibilityService?): Boolean {
        if (service == null) {
            return false
        }
        try {
            val path = Path()
            path.moveTo(X.toFloat(), Y.toFloat())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val builder = GestureDescription.Builder().addStroke(StrokeDescription(path, 0, MMKVConst.clickDu_Time))
                return service.dispatchGesture(builder.build(), null, null)
            } else {
                return false
            }
        } catch (e: Exception) {
            return false
        }



    }

    @JvmStatic
    fun sendLog(sendText: String) {
        LogWrapper.logAppend(sendText)
    }


    private var lock: PowerManager.WakeLock? = null
    private var unLock: PowerManager.WakeLock? = null
    private var km: KeyguardManager? = null
    @Suppress("DEPRECATION")
    private var kl: KeyguardManager.KeyguardLock? = null

    @SuppressLint("InvalidWakeLockTag", "MissingPermission")
    @Suppress("DEPRECATION")
    @JvmOverloads
    @JvmStatic
    fun wakeUpAndUnlock(context: Context = appContext) {
        //屏锁管理器
        km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        kl = km!!.newKeyguardLock("unLock")
        //解锁
        kl!!.disableKeyguard()
        //获取电源管理器对象
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        //获取PowerManager.WakeLock对象,后面的参数|表示同时传入两个值,最后的是LogCat里用的Tag
        unLock = pm.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "bright"
        )
        //点亮屏幕
        unLock!!.acquire(1 * 1 * 66 * 1000L)
        sendLog("临时禁用锁屏")
        //        wl.acquire();
        //释放
        //        wl.release();
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @JvmStatic
    fun lockScreen() {
        kl?.let {
            // 锁屏
            it.reenableKeyguard()
            sendLog("重新恢复锁屏")
        }
        unLock?.let {
            // 释放wakeLock，关灯
            if (it.isHeld) {
                it.release()
            }
        }
    }



}
//---------------- 结果回调 ----------------
interface MoveCallback {
    fun onSuccess()
    fun onError()
}