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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.IntRange
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.window.ClickIndicatorManager
import com.google.android.accessibility.ext.window.LogWrapper
import com.google.android.accessibility.ext.window.SwipeTrajectoryIndicatorManager
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

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
    @JvmOverloads
    @JvmStatic
    fun getScreenState(context: Context = appContext): ScreenState {
        val appCtx = context.applicationContext

        val pm = appCtx.getSystemService(PowerManager::class.java)
        val isInteractive = pm?.isInteractive ?: false

        // 1️⃣ 可交互 → 一定是亮屏
        if (isInteractive) {
            return ScreenState.ON
        }

        // 2️⃣ 非交互：区分 AOD / DOZING / OFF
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dm = appCtx.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val state = display?.state ?: Display.STATE_UNKNOWN

            return when (state) {
                Display.STATE_DOZE -> ScreenState.AOD
                Display.STATE_DOZE_SUSPEND -> ScreenState.DOZING
                Display.STATE_OFF -> ScreenState.OFF
                else -> {
                    val isIdle = try {
                        pm?.isDeviceIdleMode ?: false
                    } catch (_: Throwable) {
                        false
                    }
                    if (isIdle) ScreenState.DOZING else ScreenState.OFF
                }
            }
        }

        // 3️⃣ 低版本兜底
        return ScreenState.OFF
    }

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
    fun getDeviceLockState(context: Context = appContext,byKeyguard: Boolean = true): DeviceLockState {
        val appCtx = context.applicationContext
        if (mKeyguardManager == null) {
            mKeyguardManager = appCtx.getSystemService(KeyguardManager::class.java)
        }
        mKeyguardManager ?: return DeviceLockState.Unlocked(isDeviceSecure = false) // 保守默认：当无法获取时当作未配置安全锁并解锁


        val deviceSecure = mKeyguardManager!!.isDeviceSecure  // 设备是否配置了 PIN/Pattern/密码/生物 等
        val deviceLocked = getDeviceLocked()
           /* try {
            // isDeviceLocked  当前设备是否处于“锁定”状态（需要验证才能访问用户数据）
            //isKeyguardLocked  UI 锁屏
            if (byKeyguard) mKeyguardManager!!.isKeyguardLocked else mKeyguardManager!!.isDeviceLocked
        } catch (_: Throwable) {
            false
        }*/

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
    fun getDeviceStatusPlus(
        context: Context = appContext,
        byKeyguard: Boolean = true
    ): DeviceStatus {
        //“先屏幕、后锁屏”的执行顺序
        return DeviceStatus(
            screenState = getScreenState(context),
            lockState = getDeviceLockState(context, byKeyguard)
        )
    }
    /**
     * isKeyguardLocked()   (屏幕被锁屏页覆盖?)
     * isDeviceSecure()     (设置了密码?)
     * isDeviceLocked()     (当前被安全锁定?)
     *
     * isKeyguardLocked()
     * 解释：只要手机处于“锁屏界面”（无论是黑屏唤醒后，还是手动锁屏），返回就是 true。
     * 注意：即使手机没有设置密码（例如只是“滑动解锁”），只要停留在锁屏页，它也返回 true。
     *
     * isDeviceSecure()
     * 解释：用户是否在系统设置里开启了 PIN 码、图案、密码或生物识别（指纹/人脸）。
     * 注意：它不关心屏幕现在是亮是暗。哪怕你正在玩手机（屏幕解锁状态），只要你设置过密码，这个函数永远返回 true。
     *
     * isDeviceLocked() (API 22+)
     * 解释：这是最严格的判定。它返回 true 表示设备目前处于锁定状态且需要解锁（验证）才能使用。
     * 逻辑关系：通常情况下，isDeviceLocked() = isKeyguardLocked() && isDeviceSecure()。即：屏幕锁着 且 手机有密码保护。
     *
    * */
/*    @JvmOverloads
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

        //val deviceLocked = getDeviceLocked(km,pm,byKeyguard)
        val deviceLocked = try {
            if (byKeyguard) km.isKeyguardLocked else km.isDeviceLocked
        } catch (e: Exception) {
            false
        }

        Log.e("我就看看傻", "DeviceLocked= "+km.isDeviceLocked+" Keyguard= "+km.isKeyguardLocked)
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
    }*/
    @JvmOverloads
    @JvmStatic
    fun getDeviceLocked(
        context: Context = appContext,
        byKeyguard: Boolean = true
    ): Boolean {
        if (mKeyguardManager == null) {
            //mKeyguardManager = context.applicationContext.getSystemService(KeyguardManager::class.java)
            mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }

        if (mPowerManager == null) {
            mPowerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        }
        val locked = if (getUnLockMethod()==1){
            //禁用键盘锁
            //wakeKeyguardOn()
           //解锁方案1
            if (mPowerManager!!.isInteractive){
                // 屏幕亮屏状态+设备没有设置安全pin
                // 则执行完 wakeKeyguardOn()后,可直接赋值为 未锁定
                if (!mKeyguardManager!!.isDeviceSecure){
                    false
                }else{
                    if (byKeyguard) mKeyguardManager!!.isKeyguardLocked else mKeyguardManager!!.isDeviceLocked
                }
            }else{
                true
            }
        }else{
            try {
                //恢复键盘锁
                //wakeKeyguardOff()
                if (byKeyguard) mKeyguardManager!!.isKeyguardLocked else mKeyguardManager!!.isDeviceLocked
            } catch (e: Exception) {
                false
            }
        }


        return locked
    }

    /**
     * 全局 Application 级 CoroutineScope
     * - 主线程
     * - SupervisorJob：子任务失败不影响其他任务
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
    }

    /**
     * 主动取消（一般不需要）
     * 只在明确要彻底停止全局任务时用
     */
    fun cancelAll() {
        appScope.cancel("AliveUtils cancelled")
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
    private var mPowerManager: PowerManager? = null
    @JvmOverloads
    @JvmStatic
    fun screenIsOn(context: Context = appContext): Boolean {
        var isScreenOn = false
        //是否需要亮屏唤醒屏幕
        if (mPowerManager == null) {
            mPowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        }

        if (!mPowerManager!!.isInteractive) {
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
    fun wakeScreenOn(context: Context = appContext) {

        if (mPowerManager == null) {
            mPowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        }
        val wl = mPowerManager!!.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "${context.packageName}:wake"
        ).apply {
            setReferenceCounted(false)
        }

        wl.acquire(60_000L)

        Handler(Looper.getMainLooper()).postDelayed({
            if (wl.isHeld) wl.release()
        }, 60_000L)

    }
    public var mKeyguardManager: KeyguardManager? = null
    // 1. 使用静态变量持有锁对象的引用
    private var mKeyguardLock: KeyguardManager.KeyguardLock? = null

    // 用来防止Handler内存泄露
    private val mHandler = Handler(Looper.getMainLooper())
    private val mReenableRunnable = Runnable { wakeKeyguardOff() }
    @JvmOverloads
    @JvmStatic
    fun wakeKeyguardOn(context: Context = appContext) {
        if (mKeyguardManager == null) {
            mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        // 2. 如果之前有锁，先处理掉，防止逻辑混乱
        if (mKeyguardLock == null) {
            mKeyguardLock = mKeyguardManager?.newKeyguardLock("app:unlock")
        }

        // 3. 只有持有引用的这个对象调用的 disable 才是有效的
        mKeyguardLock?.disableKeyguard()
        sendLog("无安全锁时尝试禁用键盘锁(可能失效)")

        // 4. 移除之前的延时任务，避免多次调用导致冲突
        //mHandler.removeCallbacks(mReenableRunnable)
        // 重新设置延时
        //mHandler.postDelayed(mReenableRunnable, 60_000L)


    }
    @JvmOverloads
    @JvmStatic
    fun wakeKeyguardOff(context: Context = appContext) {
        // 5. 使用同一个对象进行恢复
        mKeyguardLock?.let {
            it.reenableKeyguard()
            sendLog("恢复键盘锁")
        }
        // 释放引用（虽然 KeyguardLock 系统层未必释放，但逻辑上我们重置了）
        // 注意：有些业务场景下为了复用可能不置空，视具体情况而定
        // mKeyguardLock = null
    }

    @JvmOverloads
    @JvmStatic
    fun keyguardIsOn(context: Context = appContext): Boolean {
        var isKeyguardOn = false
        if (mKeyguardManager == null) {
            mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }

        //if (mPowerManager == null) {
            //mPowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        //}

        // mKeyguardManager?.isKeyguardLocked== true
        // getDeviceLocked(mKeyguardManager!!,mPowerManager!!,true)
        if (getDeviceLocked()){
            //键盘锁定,需要解锁
            isKeyguardOn = false
        }else{
            //键盘没锁定,不需要解锁
            isKeyguardOn = true
        }
        return isKeyguardOn
    }
    /*
    * 普通个人手机上 两个方法没区别
    * isKeyguardSecure和 isDeviceSecure
    * isKeyguardSecure = 锁屏界面本身有没有密码
isDeviceSecure = 这台设备“有没有任何安全门槛”
*
    * */
    @JvmOverloads
    @JvmStatic
    fun deviceIsSecure(context: Context = appContext,byKeyguard: Boolean =false): Boolean {
        var isSecure = false
        if (mKeyguardManager == null) {
            mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }

        if (byKeyguard){
            if (mKeyguardManager?.isKeyguardSecure==true){
                //设备安全,有密码pin
                isSecure = true
            }else{
                //设备没密码,是滑动解锁
                isSecure = false
            }
        }else{
            if (mKeyguardManager?.isDeviceSecure==true){
                //设备安全,有密码pin
                isSecure = true
            }else{
                //设备没密码,是滑动解锁
                isSecure = false
            }
        }

        return isSecure
    }
    /**
     * 其它都一样,只有 锁屏上弹出“闹钟界面”  这个时候 再进行判定
     * true（设备仍锁定isDeviceLocked）	false（Keyguard 被临时隐藏）
     * */
    @JvmOverloads
    @JvmStatic
    fun deviceIsOn(context: Context = appContext): Boolean {
        var isDeviceOn = false
        if (mKeyguardManager == null) {
            mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        if (mKeyguardManager?.isDeviceLocked==true){
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
        Log.e("解锁", "上划屏幕呼出输入解锁密码界面")
        sendLog("上划屏幕呼出输入解锁密码界面")
        //===============
        //1.向上滑动进入密码解锁界面
        val path = Path()
        path.moveTo(screenWidth / 8f, y)   //滑动起点
        path.lineTo(screenWidth / 8f, y - 800f)//滑动终点

        //===================
        move(access_Service!!, path, start, duration,
            object : MoveCallback {
                override fun onSuccess() {
                    sendLog("手势上划成功,然后开始输入密码解锁")
                    if (!TextUtils.isEmpty(password)){
                        //睡眠一下 等待 解锁界面加载出来
                        SystemClock.sleep(500)
                        //===
                        unlockScreenNew(access_Service, password)
                    }

                }

                //==============================================================
                override fun onError() {
                    sendLog("手势上划失败")
                }
            })
        return isSuc
    }

/*    @JvmStatic
    fun getScreenSize(context: Context): Pair<Int, Int> {
        val displayMetrics = context.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        return Pair(width, height)
    }*/

    @Suppress("DEPRECATION")
    @JvmStatic
    fun getScreenSize(context: Context = appContext): Pair<Int, Int> {
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ 推荐方式
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val width = bounds.width()
            val height = bounds.height()
            Pair(width, height)
        } else {
            // 旧版兼容
            val display = wm.defaultDisplay
            val outMetrics = android.util.DisplayMetrics()
            display.getRealMetrics(outMetrics) // 注意：getMetrics() 拿到的可能是去掉系统栏后的
            Pair(outMetrics.widthPixels, outMetrics.heightPixels)
        }
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

    // ---------- computeAutoDuration（智能计算时长） ----------
    /**
     * 根据滑动距离与屏幕密度计算智能时长（ms）
     */
    fun computeAutoDuration(
        context: Context,
        distancePx: Float,
        curveIntensity: Float = 0.12f,
        minMs: Long = 80L,
        maxMs: Long = 900L
    ): Long {
        val dist = max(1f, distancePx)

        val densityDpi = context.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT
        val baseDensityRef = 420f
        val baseSpeedPxPerMs = 2.5f
        val densityFactor = (densityDpi / baseDensityRef).coerceIn(0.7f, 1.4f)
        var speedPxPerMs = baseSpeedPxPerMs * densityFactor

        val distFactor = (dist / 1000f).coerceIn(0.6f, 2.0f)
        speedPxPerMs *= (1.0f + (distFactor - 1f) * 0.2f)

        val curveSlowdown = 1f + (curveIntensity.coerceIn(0f, 0.5f) * 0.6f)
        speedPxPerMs /= curveSlowdown

        val duration = (dist / speedPxPerMs).roundToLong().coerceIn(minMs, maxMs)
        return duration
    }
    /**
     * 生成一个更自然的上划手势路径（可选曲线 + 轻微随机化）
     *
     * @param context 用于获取屏幕尺寸的 Context
     * @param useCurve 是否使用曲线路径（true：cubic 曲线；false：直线）
     * @param curveIntensity 曲线强度比例（0..1），0 = 无曲线，0.1~0.2 为轻微弧线
     * @param horizontalOffsetRatio 水平偏移比例，相对于屏幕宽度（用于左右微调）
     * @param jitterRatio 随机抖动强度（0..1），用于模拟手指的不规则性
     *
     * 推荐参数：useCurve=true,
     * curveIntensity=0.08..0.15, jitterRatio=0.01..0.03，这样既自然又稳定。
     */
    @JvmOverloads
    @JvmStatic
    fun createNaturalSwipePathInfo(
        context: Context = appContext,
        useCurve: Boolean = true,
        curveIntensity: Float = 0.12f,
        horizontalOffsetRatio: Float = 0.0f,
        jitterRatio: Float = 0.02f
    ): SwipePathInfo {
        val (screenWidth, screenHeight) = getScreenSize(context) // 你已有的工具
        KeyguardUnLock.sendLog("设备的宽度= " + screenWidth + ", 高度= " + screenHeight)
        val startXBase = screenWidth / 2f
        val startYBase = screenHeight * 0.88f
        val endXBase = screenWidth / 2f + screenWidth * horizontalOffsetRatio
        val endYBase = screenHeight * 0.30f

        // 防止极端值
        val curveFactor = curveIntensity.coerceIn(0f, 0.5f)
        val jitterFactor = jitterRatio.coerceIn(0f, 0.1f)

        // 小随机，用来模拟手指微抖（每次可能不同）
        val rand = Random.Default
        fun jitter(amountRatio: Float) = (rand.nextFloat() * 2f - 1f) * amountRatio

        val startX = startXBase + screenWidth * jitter(jitterFactor)
        val startY = startYBase + screenHeight * jitter(jitterFactor * 0.5f)
        val endX = endXBase + screenWidth * jitter(jitterFactor * 0.5f)
        val endY = endYBase + screenHeight * jitter(jitterFactor * 0.2f)

        val distance = (startY - endY).coerceAtLeast(1f)

        // 控制点位置：基于距离分段，并加入曲线强度与少量水平偏移
        val cp1x = startX + screenWidth * (0.05f * curveFactor) + screenWidth * jitter(jitterFactor)
        val cp1y = startY - distance * 0.33f - screenHeight * (0.02f * curveFactor)

        val cp2x = endX - screenWidth * (0.05f * curveFactor) + screenWidth * jitter(jitterFactor)
        val cp2y = startY - distance * 0.66f + screenHeight * (0.01f * curveFactor)

        KeyguardUnLock.sendLog("模拟人手轻微抖动轨迹: start=($startX,$startY) end=($endX,$endY) cp1=($cp1x,$cp1y) cp2=($cp2x,$cp2y)")
        val path = Path().apply {
            moveTo(startX, startY)
            if (useCurve && curveFactor > 0f) {
                cubicTo(cp1x, cp1y, cp2x, cp2y, endX, endY)
            } else {
                // 直线（保留一点微抖使其不显僵硬）
                val midX = (startX + endX) / 2f + screenWidth * jitter(jitterFactor * 0.3f)
                val midY = (startY + endY) / 2f + screenHeight * jitter(jitterFactor * 0.3f)
                lineTo(midX, midY)
                lineTo(endX, endY)
            }
        }
        return  SwipePathInfo(path, startX, startY, endX, endY)

    }
    // 🟡【新增】互斥锁，防止手势并发重叠执行
    private val moveMutex = Mutex()
    @JvmOverloads
    @JvmStatic
   /* suspend fun moveAwait(
        service: AccessibilityService? =accessibilityService,
        pathInfo: SwipePathInfo? = null,
        @IntRange(from = 0) startTime: Long =500,
        @IntRange(from = 0) duration: Long =500, // <=0 表示自动计算
        moveCallback: MoveCallback? = null,
        timeoutMs: Long = 2000L,
        autoDurationEnabled: Boolean = true,
        // createNaturalSwipePathInfo 可配置参数（如需自定义）
        useCurve: Boolean = true,
        curveIntensity: Float = 0.12f,
        horizontalOffsetRatio: Float = 0.0f,
        jitterRatio: Float = 0.02f
    ): Boolean = withContext(Dispatchers.Main) {
        if (startTime < 0 || duration < 0) {
            moveCallback?.onError()
            return@withContext false
        }

        if (service == null) {
            KeyguardUnLock.sendLog("无障碍服务未开启!")
            return@withContext false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            KeyguardUnLock.sendLog("系统版本小于7.0")
            return@withContext false
        }
        // 如果没有传入 pathInfo，则生成一个默认的
        val finalPathInfo = pathInfo ?: createNaturalSwipePathInfo(
            context = service.applicationContext,
            useCurve = useCurve,
            curveIntensity = curveIntensity,
            horizontalOffsetRatio = horizontalOffsetRatio,
            jitterRatio = jitterRatio
        )

        // 真实像素距离（Y 方向）
        val distancePx = abs(finalPathInfo.startY - finalPathInfo.endY)

        // 选择时长：传入 duration >0 优先；否则若允许自动计算则计算
        val finalDuration = if (duration > 0L) {
            duration.coerceAtLeast(50L)
        } else if (autoDurationEnabled) {
            // 智能计算（min/max 可按需调整）
            computeAutoDuration(
                context = service.applicationContext,
                distancePx = distancePx,
                curveIntensity = curveIntensity,
                minMs = 80L,
                maxMs = 900L
            )
        } else {
            // fallback（若不自动计算且未传入时长）使用一个合理默认
            500L
        }


        showGestureIndicator(service, finalPathInfo.path, finalDuration)
        delay(60)
        KeyguardUnLock.sendLog("上划屏幕呼出输入解锁密码界面")
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(finalPathInfo.path, startTime, finalDuration))
            .build()

        // 无回调：保持原行为 -> 立即返回
        if (moveCallback == null) {
            return@withContext try {
                service.dispatchGesture(gesture, null, null)
                true
            } catch (_: Throwable) {
                false
            }
        }

        // 有回调：等待结果（可选超时）
        val block: suspend () -> Boolean = suspend {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        try {
                            moveCallback.onSuccess()
                        } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        try {
                            moveCallback.onError()
                        } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(false)
                    }
                }

                try {
                    service.dispatchGesture(gesture, callback, null)
                } catch (_: Throwable) {
                    try {
                        moveCallback.onError()
                    } catch (_: Throwable) {}
                    if (cont.isActive) cont.resume(false)
                }

                cont.invokeOnCancellation {
                    // 不额外调用回调，保持原语义
                }
            }
        }

        if (timeoutMs > 0) {
            withTimeoutOrNull(timeoutMs) { block() } ?: false
        } else {
            block()
        }
    }*/

    suspend fun moveAwait(
        service: AccessibilityService? = accessibilityService,
        pathInfo: SwipePathInfo? = null,
        @IntRange(from = 0) startTime: Long = 500,
        @IntRange(from = 0) duration: Long = 500,
        moveCallback: MoveCallback? = null,
        timeoutMs: Long = 2000L,
        autoDurationEnabled: Boolean = true,
        useCurve: Boolean = true,
        curveIntensity: Float = 0.12f,
        horizontalOffsetRatio: Float = 0.0f,
        jitterRatio: Float = 0.02f,
        retryCount: Int = 1 // 🟡【新增】重试次数配置（默认重试 1 次）
    ): Boolean = withContext(Dispatchers.Default) {

        moveMutex.withLock {
            var attempt = 0
            var result = false

            // 🟡【新增】增加循环重试逻辑
            while (attempt <= retryCount && !result) {
                attempt++

                // ====== 1️⃣ 参数和前置检查 ======
                if (startTime < 0 || duration < 0) {
                    moveCallback?.onError()
                    return@withContext false
                }

                if (service == null) {
                    KeyguardUnLock.sendLog("无障碍服务未开启!")
                    return@withContext false
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    KeyguardUnLock.sendLog("系统版本小于7.0")
                    return@withContext false
                }

                val finalPathInfo = pathInfo ?: createNaturalSwipePathInfo(
                    context = service.applicationContext,
                    useCurve = useCurve,
                    curveIntensity = curveIntensity,
                    horizontalOffsetRatio = horizontalOffsetRatio,
                    jitterRatio = jitterRatio
                )

                val distancePx = abs(finalPathInfo.startY - finalPathInfo.endY)
                val finalDuration = when {
                    duration > 0L -> duration.coerceAtLeast(50L)
                    autoDurationEnabled -> computeAutoDuration(
                        context = service.applicationContext,
                        distancePx = distancePx,
                        curveIntensity = curveIntensity,
                        minMs = 80L,
                        maxMs = 900L
                    )
                    else -> 500L
                }

                // ====== 2️⃣ 在主线程执行手势 ======
                result = safeRunOnMain {
                    showGestureIndicator(service, finalPathInfo.path, finalDuration)
                    delay(60)
                    KeyguardUnLock.sendLog("上划屏幕呼出输入解锁密码界面")

                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(finalPathInfo.path, startTime, finalDuration))
                        .build()

                    if (moveCallback == null) {
                        try {
                            service.dispatchGesture(gesture, null, null)
                            return@safeRunOnMain true
                        } catch (_: Throwable) {
                            return@safeRunOnMain false
                        }
                    }

                    val block: suspend () -> Boolean = suspend {
                        suspendCancellableCoroutine { cont ->
                            val callback = object : AccessibilityService.GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription) {
                                    try { moveCallback.onSuccess() } catch (_: Throwable) {}
                                    if (cont.isActive) cont.resume(true)
                                }

                                override fun onCancelled(gestureDescription: GestureDescription) {
                                    try { moveCallback.onError() } catch (_: Throwable) {}
                                    if (cont.isActive) cont.resume(false)
                                }
                            }

                            try {
                                service.dispatchGesture(gesture, callback, null)
                            } catch (_: Throwable) {
                                try { moveCallback.onError() } catch (_: Throwable) {}
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                    }

                    if (timeoutMs > 0) {
                        withTimeoutOrNull(timeoutMs) { block() } ?: false
                    } else {
                        block()
                    }
                }

                // 🟡【新增】失败重试提示
                if (!result && attempt <= retryCount) {
                    KeyguardUnLock.sendLog("手势执行失败，第${attempt}次重试中…")
                    delay(200L) // 稍微等一等再重试
                }
            }

            result
        }
    }

    /**
     * 自动判断当前线程，必要时切回主线程执行。
     * 这样无论从哪个协程上下文调用，都能安全执行 UI 操作。
     */
    suspend inline fun <T> safeRunOnMain(crossinline block: suspend CoroutineScope.() -> T): T {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            coroutineScope { block() } // 已在主线程，直接执行
        } else {
            withContext(Dispatchers.Main) { block() } // 切到主线程
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
        //默认为false 也即旧版old
       return MMKVUtil.get(MMKVConst.UNLOCK_METHOD,false)
    }
    @JvmStatic
    fun setUnLockOld_slide(isNew: Boolean = false) {
        MMKVUtil.put(MMKVConst.KEY_JIESUO_1_BY,isNew)
    }
    @JvmStatic
    fun getUnLockOld_slide(): Boolean {
        return MMKVUtil.get(MMKVConst.KEY_JIESUO_1_BY,false)
    }
    @JvmOverloads
    @JvmStatic
    fun setUnLockMethod(isNew: Int = 0) {
        //切换不同的解锁方案的时候刷新一下键盘锁
        if (isNew == 1){wakeKeyguardOn()}
        else if (isNew == 0){wakeKeyguardOff()}
        else if (isNew == 2){wakeKeyguardOff()}
        else if (isNew == 3){wakeKeyguardOff()}
        MMKVUtil.put(MMKVConst.KEY_JIESUO_METHOD_NUMBERPICKER,isNew)
    }
    @JvmOverloads
    @JvmStatic
    fun getUnLockMethod(default: Int = 0): Int {
        return MMKVUtil.get(MMKVConst.KEY_JIESUO_METHOD_NUMBERPICKER,default)
    }

    @JvmOverloads
    @JvmStatic
    fun setAutoReenKeyguard(isAuto: Boolean = true) {
        MMKVUtil.put(MMKVConst.KEY_AutoReenKeyguard,isAuto)
    }
    @JvmOverloads
    @JvmStatic
    fun getAutoReenKeyguard(default: Boolean = true): Boolean {
        return MMKVUtil.get(MMKVConst.KEY_AutoReenKeyguard,default)
    }

    @JvmOverloads
    @JvmStatic
    fun setAutoDisableKeyguard(isAuto: Boolean = false) {
        MMKVUtil.put(MMKVConst.KEY_AutoDisableKeyguard,isAuto)
    }
    @JvmOverloads
    @JvmStatic
    fun getAutoDisableKeyguard(default: Boolean = false): Boolean {
        return MMKVUtil.get(MMKVConst.KEY_AutoDisableKeyguard,default)
    }

    @JvmOverloads
    @JvmStatic
    fun setScreenAlwaysOn(alwaysOn: Boolean = false) {
        MMKVUtil.put(MMKVConst.XPQ_SCREEN_ON,alwaysOn)
    }
    @JvmOverloads
    @JvmStatic
    fun getScreenAlwaysOn(default: Boolean = false): Boolean {
        return MMKVUtil.get(MMKVConst.XPQ_SCREEN_ON,default)
    }

    @JvmOverloads
    @JvmStatic
    fun setScreenPassWord(pwd: String = "1234") {
        MMKVUtil.put(MMKVConst.KEY_LOCK_SCREEN_PASSWORD,pwd)
        MMKVUtil.put(MMKVConst.KEY_JIESUO_MIMA,pwd)
    }
    @JvmOverloads
    @JvmStatic
    fun getScreenPassWord(default: String = "1234"): String {
        return MMKVUtil.get(MMKVConst.KEY_LOCK_SCREEN_PASSWORD,default)
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
    fun showClickIndicator(service: AccessibilityService?, x: Int, y: Int) {
        service?: return
        val showguiji = MMKVUtil.get(MMKVConst.SHOW_DO_GUIJI, false)
        if (showguiji){
            // 在主线程显示指示器
            Handler(Looper.getMainLooper()).post {
                ClickIndicatorManager.show(service, x, y)
            }
        }

    }

    @JvmStatic
    fun showGestureIndicator(service: AccessibilityService?, path: Path, duration: Long) {
        service?: return
        val showguiji = MMKVUtil.get(MMKVConst.SHOW_DO_GUIJI, false)
        if (showguiji){
            // 在主线程显示指示器
            Handler(Looper.getMainLooper()).post {
                SwipeTrajectoryIndicatorManager.show(service, path, duration = 600L)

            }
        }

    }

    @JvmOverloads
    @JvmStatic
    fun performClickNodeInfo(
        service: AccessibilityService?,
        nodeInfo: AccessibilityNodeInfo? ,
        isMoNi: Boolean = true
    ): Boolean {
        service?: return false
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


    @Volatile
    private var lastClickTime = 0L
    @JvmOverloads
    @JvmStatic
    fun canClick(interval: Long = 500L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime < interval) return false
        lastClickTime = now
        return true
    }

    @JvmOverloads
    @JvmStatic
    fun xpqclickNode(
        service: AccessibilityService?,
        nodeInfo: AccessibilityNodeInfo?,
        isMoNi: Boolean = true ,
        interval: Long = 0L
    ): Boolean {
        service?: return false
        if (nodeInfo == null) return false
        if (!canClick(interval)) return false

        // 在后台执行点击操作
        clickScope.launch {
            try {
                if (isMoNi) {
                    doNoniClick(nodeInfo,service)
                } else {
                    // 执行原生点击
                    val b = nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!b){
                        doNoniClick(nodeInfo,service)
                    }else{
                        // 只有在SHOW_DO_GUIJI为true时才显示点击指示器
                        if (MMKVUtil.get(MMKVConst.SHOW_DO_GUIJI, false)) {
                            val (xCenter, yCenter) = getNodeCenter(nodeInfo)
                            // 在主线程显示点击指示器
                            showClickIndicator(service, xCenter, yCenter)
                        }
                    }

                }
            } catch (t: Throwable) {
                // 处理异常，记录日志等
                Log.e("XPQClickNode", "点击操作失败: ${nodeInfo?.text}", t)
            }
        }

        return true
    }
    @JvmStatic
    fun doNoniClick(nodeInfo: AccessibilityNodeInfo,service: AccessibilityService) {
        // 模拟点击
        val (xCenter, yCenter) = getNodeCenter(nodeInfo)
        moniClick(xCenter, yCenter, service)
        // 在主线程显示点击指示器
        showClickIndicator(service, xCenter, yCenter)
    }
    // 提取方法，获取点击坐标
    @JvmStatic
    fun getNodeCenter(nodeInfo: AccessibilityNodeInfo): Pair<Int, Int> {
        val rect = Rect().apply { nodeInfo.getBoundsInScreen(this) }
        return abs(rect.centerX()) to abs(rect.centerY())
    }




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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { moniClick(X, Y, service) }
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

        //获取电源管理器对象
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        //获取PowerManager.WakeLock对象,后面的参数|表示同时传入两个值,最后的是LogCat里用的Tag
        unLock = pm.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "bright"
        )
        //点亮屏幕
        unLock!!.acquire( 60 * 1000L)
        Handler(Looper.getMainLooper()).postDelayed({
            if (unLock?.isHeld == true) {
                unLock?.release()
            }
        }, 60 * 1000L)
        //屏锁管理器
        km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        kl = km!!.newKeyguardLock("unLock")
        //无安全锁时  解除键盘锁
        kl!!.disableKeyguard()
        sendLog("无安全锁时尝试解除锁屏(可能失效)")

    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @JvmStatic
    fun lockScreen() {
        Handler(Looper.getMainLooper()).postDelayed({
            kl?.let {
                // 恢复键盘锁
                it.reenableKeyguard()
                sendLog("无安全锁时尝试恢复锁屏")
            }
            unLock?.let {
                // 释放wakeLock，关灯
                if (it.isHeld) {
                    it.release()
                }
            }
        }, 60 * 1000L)


    }



}
// ---------- 数据类：包含 Path 与起终点信息 ----------
data class SwipePathInfo(
    val path: Path,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

//---------------- 结果回调 ----------------
interface MoveCallback {
    fun onSuccess()
    fun onError()
}