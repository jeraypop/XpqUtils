package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.google.android.accessibility.ext.activity.AliveActivity
import com.google.android.accessibility.ext.task.PERIOD
import com.google.android.accessibility.ext.task.TIMEOUT
import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.window.AssistsWindowManager
import com.google.android.accessibility.ext.window.ClickIndicatorManager

import com.google.android.accessibility.ext.window.LogWrapper
import com.google.android.accessibility.ext.window.SwipeTrajectoryIndicatorManager
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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


    //只在设备 不安全锁时调用   disableKeyguard 为 true
    //                      reenableKeyguard 为 false
    val keyguardIsGone = AtomicBoolean(false)
    //只在系统屏幕广播调用 真正完全解锁和关闭
    //val keyguardIsGone100 = AtomicBoolean(false)
    //只在系统屏幕广播调用 点亮和关闭
    //val suoPingIsOne = AtomicBoolean(false)
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
    fun getDeviceLockState(context: Context = appContext,byKeyguard: Boolean = true,jian: Boolean = false): DeviceLockState {
        val appCtx = context.applicationContext
        if (mKeyguardManager == null) {
            mKeyguardManager = appCtx.getSystemService(KeyguardManager::class.java)
        }
        mKeyguardManager ?: return DeviceLockState.Unlocked(isDeviceSecure = false) // 保守默认：当无法获取时当作未配置安全锁并解锁


        val deviceSecure = mKeyguardManager!!.isDeviceSecure  // 设备是否配置了 PIN/Pattern/密码/生物 等
        val deviceLocked = getDeviceLocked(jian = jian)
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
        byKeyguard: Boolean = true,
        jian: Boolean = false
    ): DeviceStatus {
        //“先屏幕、后锁屏”的执行顺序
        return DeviceStatus(
            screenState = getScreenState(context),
            lockState = getDeviceLockState(context, byKeyguard, jian)
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

    @JvmOverloads
    @JvmStatic
    fun getDeviceLocked(
        context: Context = appContext,
        byKeyguard: Boolean = true,
        jian: Boolean = false
    ): Boolean {
        if (mKeyguardManager == null) {
            //mKeyguardManager = context.applicationContext.getSystemService(KeyguardManager::class.java)
            mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }

        if (mPowerManager == null) {
            mPowerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        }
        var locked = false
        //jian
        locked = if (false) {
            try {
                if (byKeyguard) mKeyguardManager!!.isKeyguardLocked else mKeyguardManager!!.isDeviceLocked
            } catch (e: Exception) {
                false
            }
        } else {
            //解锁方案0和1
            if (getUnLockMethod() == 0 || getUnLockMethod() == 1) {
                //设备无安全锁
                if (!deviceIsSecure()){
                    //屏幕亮屏
                    if (screenIsOn()) {
                        !keyguardIsGone.get()//注意 keyguardIsGone的值是指键盘是否已解锁,要取反
                    } else {
                        //黑屏,直接判断 键盘锁住
                        true
                    }
                }else{
                    try {
                        if (byKeyguard) mKeyguardManager!!.isKeyguardLocked else mKeyguardManager!!.isDeviceLocked
                    } catch (e: Exception) {
                        false
                    }
                }

            } else {
                try {
                    if (byKeyguard) mKeyguardManager!!.isKeyguardLocked else mKeyguardManager!!.isDeviceLocked
                } catch (e: Exception) {
                    false
                }
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
            SupervisorJob() + Dispatchers.Default
        )
    }
    @Volatile
    private var unlockJob: Job? = null


    fun startUnlockTask(block: suspend CoroutineScope.() -> Unit) {
        // 🔴 关键：先取消上一次
        unlockJob?.cancel()

        unlockJob = appScope.launch {
            try {
                block()
            } finally {
                // 🔴 确保这个任务的子协程全灭
                coroutineContext.cancelChildren()
            }
        }
    }

    /**
     * 主动取消（一般不需要）
     * 只在明确要彻底停止全局任务时用
     */
    fun cancelAll() {
        appScope.cancel("AliveUtils cancelled")
    }

    suspend fun getUnLockResult(
        isUnLock: Boolean = false,
        zQSuccess: Boolean = getZQSuccess()
    ): Boolean {
        val eWai = if (zQSuccess){
            //不额外
            sendLog("解锁成功判定更宽松(不额外判定键盘锁状态)")
            isUnLock || waitKeyguardOn()

        }else{
            //额外
            sendLog("解锁成功判定更严格(附加判定键盘锁状态)")
            isUnLock && waitKeyguardOn()

        }
        return eWai
    }

    // 保留工具方法
    suspend fun <T>delayAction(delayMillis: Long = 500L, block: suspend () -> T): T {
        delay(delayMillis)
        return block()
    }

    /**
     * 判断是否亮屏
     * 如果黑屏,调用 旧版 点亮方法
     */
    suspend fun waitScreenLiang(timeOutMillis: Long = 1500L,
        periodMillis: Long = 50L
    ): Boolean {
        return delayAction(10) {
            retryCheckTaskWithLog("判断是否点亮屏幕",timeOutMillis,periodMillis,true) {
                val status = KeyguardUnLock.getScreenState()
                // 访问屏幕状态
                when (status) {
                    ScreenState.ON ->{
                        sendLog("屏幕亮屏状态")
                        true
                    }
                    ScreenState.AOD -> {
                        KeyguardUnLock.wakeScreenOn()
                        sendLog("设备 AOD 模式,需要唤醒")
                        false
                    }
                    ScreenState.DOZING -> {
                        KeyguardUnLock.wakeScreenOn()
                        sendLog("设备 Doze 模式中(可能引起定时不准),需要唤醒")
                        false
                    }
                    ScreenState.OFF -> {
                        KeyguardUnLock.wakeScreenOn()
                        sendLog("屏幕关闭状态,需要唤醒")
                        false
                    }
                    ScreenState.UNKNOWN -> {
                        KeyguardUnLock.wakeScreenOn()
                        sendLog("未知状态,需要唤醒")
                        false
                    }
                }
            }
        }
    }
    /**
     * 判断是否解除键盘锁
     *
     */
    suspend fun waitKeyguardOn(jian: Boolean = false,
                               timeOutMillis: Long = 1500L,
                               periodMillis: Long = 50L
    ): Boolean {
        return delayAction(10) {
            retryCheckTaskWithLog("判断是否解除键盘锁",timeOutMillis,periodMillis,true) {
                KeyguardUnLock.keyguardIsOn(jian = jian)
            }
        }
    }

    suspend fun waitJianPanUnLock(pwd: String = getScreenPassWord(),
                                  unLockMethod: Int = getUnLockMethod(),
                                  timeOutMillis: Long = TIMEOUT,
                                  periodMillis: Long = PERIOD
    ): Boolean {
        return delayAction(10) {
            retryCheckTaskWithLog("等待解除锁定屏幕",timeOutMillis,periodMillis) {
                var isOn = false
                val status = KeyguardUnLock.getDeviceLockState()
                // 访问锁状态
                when (val lockState = status) {
                    is DeviceLockState.Unlocked -> {
                        val msg = if (lockState.isDeviceSecure) "设备已被解锁（有安全锁）,即将执行后续操作" else "设备已被解锁（无安全锁）,即将执行后续操作"
                        sendLog(msg)
                        isOn = true
                    }
                    DeviceLockState.LockedNotSecure -> {
                        //设备被锁屏了，但是没有安全锁  {如“滑动解锁”或无锁屏}
                        sendLog("设备被锁屏,未设置安全锁,[可能是 滑动解锁或无锁屏]")
                        sendLog("准备上划解锁")
                        if (unLockMethod == 0 || unLockMethod == 1){
                            //旧版 禁用键盘锁
                            KeyguardUnLock.wakeKeyguardOn()
                            //是否额外判断键盘锁
                            val eWai = KeyguardUnLock.getUnLockResult(true)
                            isOn = eWai
                        }else if (unLockMethod == 2){
                            //上划
                            val huaok = KeyguardUnLock.moveAwait(
                                service = accessibilityService,
                                moveCallback = object : MoveCallback {
                                    override fun onSuccess() {
                                        sendLog("上划完成")
                                    }

                                    override fun onError() {
                                        sendLog("上划取消或失败")
                                    }
                                }
                            )
                            //是否额外判断键盘锁
                            val eWai = KeyguardUnLock.getUnLockResult(huaok)
                            isOn = eWai
                        }


                    }
                    DeviceLockState.LockedSecure -> {
                        //设备被锁屏了，并且有安全锁 （如 PIN、图案、指纹、人脸）
                        sendLog("设备被锁屏,设置了安全锁 [PIN、图案、密码、指纹、Face ID 等]")
                        sendLog("准备上划,呼出锁屏输入解锁密码界面")
                        //上划
                        val huaOK = KeyguardUnLock.moveAwait(
                            service = accessibilityService,
                            moveCallback = object : MoveCallback {
                                override fun onSuccess() {
                                    println("🟢 手势完成")
                                    sendLog("上划完成")
                                }

                                override fun onError() {
                                    println("🔴 手势取消或失败")
                                    sendLog("上划取消或失败")
                                }
                            }
                        )
                        if (huaOK){
                            sendLog("屏幕上划成功")
                            delay(500)
                            //输入密码
                            val inputOK = KeyguardUnLock.inputPassword(password = pwd)
                            //是否额外判断键盘锁
                            val eWai = KeyguardUnLock.getUnLockResult(inputOK)
                            isOn = eWai
                        }


                    }
                }
                isOn
            }
        }
    }

    /**
     * 在主线程中执行指定的函数
     * @param action 要在主线程执行的函数
     * @param onError 异常处理函数
     */
    fun runOnUiThread(action: () -> Unit, onError: (Throwable) -> Unit = {}) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                Handler(Looper.getMainLooper()).post {
                    action()
                }
            }
        } catch (t: Throwable) {
            onError(t)
        }
    }

/*    suspend fun waitForKeyguardOnCheck(
        times: Int = 15,
        intervalMs: Long = 100L
    ): Boolean {
        repeat(times) { attempt ->
            if (KeyguardUnLock.keyguardIsOn()) {
                sendLog("键盘已解锁")
                return true
            }
            if (attempt < times - 1) delay(intervalMs)
        }
        return false
    }*/

    @JvmStatic
    fun getLockID(): String {
        val brand = Build.BRAND.uppercase(Locale.getDefault()) //获取设备品牌
        Log.e("解锁", "手机品牌 = " + brand)

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
        sendLog("手机品牌 = " + brand+" 采用默认解锁界面节点id= "+id)
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

    private fun findLockViewOld(nodeInfo: AccessibilityNodeInfo): String? {
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
            sendLog("本次查询到解锁界面节点id= "+viewIdName)
            return viewIdName
        }

        // 递归遍历子节点
        for (i in 0 until nodeInfo.childCount) {
            nodeInfo.getChild(i)?.let { childNode ->
                val result = findLockViewOld(childNode)
                if (result != null) {
                    return result
                }
            }
        }

        return null
    }

    private fun findLockView(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()

            val className = node.className?.toString() ?: ""
            val viewIdName = node.viewIdResourceName ?: ""

            if (isTargetNode(className, viewIdName)) {
                sendLog("本次查询到解锁界面节点id= $viewIdName")
                return viewIdName
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }

        return null
    }

    private fun isTargetNode(className: String, viewId: String): Boolean {

        // 直接用 ignoreCase，不再 lowercase()
        val isMatchClass =
            className.contains("Text", true) ||
                    className.contains("Button", true) ||
                    className.contains("Chip", true)

        val isMatchId =
            viewId.contains("digitText", true) ||
                    viewId.contains("digit_text", true)

        return isMatchClass && isMatchId
    }





    /*
    *  闹钟是否会被覆盖，取决于：AlarmManager 认为你的 PendingIntent 是不是“同一个”
    *  跟你设置的时间、extras、Alarm 类型 几乎没关系
    *  ✅ 参与比较的字段（非常重要）

        Intent 的 component

        target Service / Receiver / Activity

        Intent action

        Intent data（URI）

        Intent categories

        requestCode

        PendingIntent 类型

        getService / getBroadcast / getActivity / getForegroundService
    *
    *
    * */
    const val SET_ALARM_ACTION_XPQ = "SET_ALARM_ACTION_XPQ"
    const val ALARM_ID = "ALARM_ID"
    @SuppressLint("MissingPermission")
/*    @JvmStatic
    @JvmOverloads
    fun setSendAlarm(triggerTimeMillis: Long,
                         alarm_id: Int = 0,
                         actionAlarm: String = SET_ALARM_ACTION_XPQ,
                         isAlarmClock: Boolean = true,
                         isForgroundService: Boolean = false,
                         alarmReceiver: Class<*>? = null,
                         alarmService: Class<*>? = null,
                         activity: Class<*>? = AliveActivity::class.java
    ) {
        val context = appContext?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 1. 准备 PendingIntent，用于闹钟触发时发送广播
        val alarmPI = if (alarmReceiver!= null){
            val intent = Intent(context, alarmReceiver).apply {
                action = actionAlarm
                putExtra(ALARM_ID, alarm_id)
            }
            PendingIntent.getBroadcast(
                context,
                alarm_id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )


        }else if (alarmService != null){
            val intent = Intent(context, alarmService).apply {
                action = actionAlarm
                putExtra(ALARM_ID, alarm_id)
            }
            if (isForgroundService){
                // Android 8.0 (Oreo) 及以上必须使用 getForegroundService
                // 这样系统才会允许你在后台直接启动服务，并给予你 5秒钟 的豁免期去显示通知
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        context,
                        alarm_id,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    // 8.0 以下普通 Service 即可
                    PendingIntent.getService(
                        context,
                        alarm_id,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
            }else{
                PendingIntent.getService(
                    context,
                    alarm_id,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }



        }else{
            null
        }
        alarmPI ?: return
        fun setAlarmTask(isAlarmClock: Boolean = true) {
           if (isAlarmClock){
               // 2. 准备 "点击闹钟图标" 跳转的 Intent (这是 setAlarmClock 必须的参数)
               // 当用户在系统状态栏下拉看到闹钟信息点击时，会跳转到这个 Activity
               val infoIntent = Intent(context, activity).apply {
                   action = actionAlarm
                   putExtra(ALARM_ID, alarm_id)
               }
               val infoPendingIntent = PendingIntent.getActivity(
                   context,
                   alarm_id,
                   infoIntent,
                   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
               )
               // 3. 设置 AlarmClockInfo
               val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTimeMillis, infoPendingIntent)
               alarmManager.setAlarmClock(alarmClockInfo, alarmPI)
           }else{
               alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, alarmPI)
           }
        }

        // 4. 发射！(这种方式会在系统状态栏显示一个小闹钟图标)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                setAlarmTask(isAlarmClock)
            } else {
                // 引导用户去设置页面开启 "闹钟和提醒" 权限
                 //intent: Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
            }
        } else {
            setAlarmTask(isAlarmClock)
        }
    }*/

    const val ADVANCE_OFFSET = 20_000  // 提前20秒防ROM延迟

    @JvmOverloads
    @JvmStatic
    fun setSendAlarm(
        triggerTimeMillis: Long,
        alarm_id: Int = 0,
        actionAlarm: String = SET_ALARM_ACTION_XPQ,
        isAlarmClock: Boolean = true,
        isForgroundService: Boolean = false,
        alarmReceiver: Class<*>? = null,
        alarmService: Class<*>? = null,
        activity: Class<*>? = AliveActivity::class.java
    ) {
        val context = appContext ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Android12+ exact alarm 权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) return

        fun setAlarm(trigger: Long, id: Int) {
            val alarmPI = buildPendingIntent(
                context,
                id,
                actionAlarm,
                isForgroundService,
                alarmReceiver,
                alarmService
            ) ?: return

            if (isAlarmClock && activity != null) {
                val infoIntent = Intent(context, activity).apply {
                    action = actionAlarm
                    putExtra(ALARM_ID, id)
                }
                val infoPI = PendingIntent.getActivity(
                    context,
                    id,
                    infoIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmClockInfo = AlarmManager.AlarmClockInfo(trigger, infoPI)
                alarmManager.setAlarmClock(alarmClockInfo, alarmPI)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    trigger,
                    alarmPI
                )
            }
        }

        // 提前闹钟
        //setAlarm(triggerTimeMillis - ADVANCE_OFFSET, alarm_id + 100_000)

        // 正常闹钟
        setAlarm(triggerTimeMillis, alarm_id)
    }

    @JvmStatic
    @JvmOverloads
    fun cancelSendAlarm(
        alarm_id: Int,
        actionAlarm: String = SET_ALARM_ACTION_XPQ,
        alarmReceiver: Class<*>? = null,
        alarmService: Class<*>? = null,
        isForgroundService: Boolean = false
    ) {
        val context = appContext ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        fun cancelAlarm(id: Int) {
            val pi = buildPendingIntent(
                context,
                id,
                actionAlarm,
                isForgroundService,
                alarmReceiver,
                alarmService
            )
            pi?.let { alarmManager.cancel(it) }
        }

        // 同时取消提前和正常闹钟
        cancelAlarm(alarm_id)
        //cancelAlarm(alarm_id + 100_000)
    }

    private fun buildPendingIntent(
        context: Context,
        alarmId: Int,
        action: String,
        isForeground: Boolean,
        receiver: Class<*>?,
        service: Class<*>?
    ): PendingIntent? {
        val intent = Intent().apply {
            setPackage(context.packageName)
            this.action = action
            putExtra(ALARM_ID, alarmId)
        }
        return when {
            receiver != null -> {
                intent.setClass(context, receiver)
                PendingIntent.getBroadcast(
                    context,
                    alarmId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            service != null -> {
                intent.setClass(context, service)
                if (isForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        context,
                        alarmId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getService(
                        context,
                        alarmId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
            }
            else -> null
        }
    }

    private var lastTrigger = 0L

    @JvmStatic
    @Synchronized
    fun allowTrigger(intervalMillis: Long = 30_000L): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < intervalMillis) return false
        lastTrigger = now
        return true
    }




    private var mPowerManager: PowerManager? = null

    @JvmOverloads
    @JvmStatic
    fun deviceIsDoze(context: Context = appContext): Boolean {
        var deviceIsDoze = false
        //是否需要亮屏唤醒屏幕
        if (mPowerManager == null) {
            mPowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        }

        if (!mPowerManager!!.isDeviceIdleMode) {
            //设备活跃
            deviceIsDoze = false
        }else{
            //设备doze
            deviceIsDoze = true
        }
        return deviceIsDoze
    }
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
    @JvmOverloads
    @JvmStatic
    fun wakeCPU(context: Context = appContext) {

        if (mPowerManager == null) {
            mPowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        }
        val wl = mPowerManager!!.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:wakecpu"
        ).apply {
            setReferenceCounted(false)
        }
        //自动释放
        wl.acquire(30_000L)

    }
    public var mKeyguardManager: KeyguardManager? = null
    // 1. 使用静态变量持有锁对象的引用
    private var mKeyguardLock: KeyguardManager.KeyguardLock? = null

    // 用来防止Handler内存泄露
    private val mHandler = Handler(Looper.getMainLooper())
    private val mReenableRunnable = Runnable { wakeKeyguardOff() }
    @SuppressLint("MissingPermission")
    @JvmOverloads
    @JvmStatic
    fun wakeKeyguardOn(context: Context = appContext,tip: String = "") {
        if (mKeyguardManager == null) {
            mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        // 2. 如果之前有锁，先处理掉，防止逻辑混乱
        if (mKeyguardLock == null) {
            mKeyguardLock = mKeyguardManager?.newKeyguardLock("app:unlock")
        }

        // 3. 只有持有引用的这个对象调用的 disable 才是有效的
        if (!deviceIsSecure()){
            val unLockMethod = KeyguardUnLock.getUnLockMethod()
            if (unLockMethod == 0 || unLockMethod == 1){
                if (hasSomePermission()){
                    mKeyguardLock?.disableKeyguard()
                    keyguardIsGone.set(true)
                    sendLog("$tip 无安全锁时尝试禁用键盘锁(可能失效)")
                }

            }

        }


        // 4. 移除之前的延时任务，避免多次调用导致冲突
        //mHandler.removeCallbacks(mReenableRunnable)
        // 重新设置延时
        //mHandler.postDelayed(mReenableRunnable, 60_000L)


    }
    @SuppressLint("MissingPermission")
    @JvmOverloads
    @JvmStatic
    fun wakeKeyguardOff(context: Context = appContext,tip: String = "") {
        // 5. 使用同一个对象进行恢复
        mKeyguardLock?.let {
            if (!deviceIsSecure()){
                val unLockMethod = KeyguardUnLock.getUnLockMethod()
                if (unLockMethod == 0 || unLockMethod == 1){
                    if (hasSomePermission()){
                        it.reenableKeyguard()
                        keyguardIsGone.set(false)
                        sendLog("$tip 恢复键盘锁")
                    }

                }

            }

        }
        // 释放引用（虽然 KeyguardLock 系统层未必释放，但逻辑上我们重置了）
        // 注意：有些业务场景下为了复用可能不置空，视具体情况而定
        // mKeyguardLock = null
    }
    @JvmOverloads
    @JvmStatic
    fun hasSomePermission(ctx: Context = appContext, permission: String = "android.permission.DISABLE_KEYGUARD"): Boolean {
        val declared = isDeclared(ctx, permission)
        val granted = ctx.checkCallingOrSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED

        if (!declared) {
            sendLog("$permission 权限未在清单文件中配置")
        }
        if (!granted) {
            sendLog("$permission 权限未申请")
        }


        return declared && granted
    }
    /*
    * 清单文件中是否声明
    * */
    @JvmOverloads
    @JvmStatic
    private fun isDeclared(ctx: Context = appContext, permission: String = "android.permission.DISABLE_KEYGUARD"): Boolean {
        return try {
            val info = ctx.packageManager.getPackageInfo(
                ctx.packageName,
                PackageManager.GET_PERMISSIONS
            )
            info.requestedPermissions?.contains(permission) == true
        } catch (t: Throwable) {
            false
        }
    }

    @JvmOverloads
    @JvmStatic
    fun keyguardIsOn(context: Context = appContext,jian: Boolean = false): Boolean {
        var isKeyguardOn = false
        if (mKeyguardManager == null) {
            mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        if (getDeviceLocked(jian = jian)){
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
    fun inputPassword(access_Service: AccessibilityService? = accessibilityService, password: String="", isJava: Boolean = false, isNew: Boolean = true): Boolean {
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

        if (isNew){
            isSuc = JieSuoUtils.handleLockScreen(myDigit = password)
        }else{
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
                        s.toString(),
                        isJava
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
            jiesuoRun(getLockViewID(access_Service.rootInActiveWindow))
            //===================
        }



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
    fun unlockMove(access_Service: AccessibilityService? = accessibilityService, start: Long=500L, duration: Long=500L, password: String="",isJava: Boolean = false): Boolean {
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
                        inputPassword(access_Service, password, isJava)
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
 /*   suspend fun moveAwait(
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
        retryCount: Int = 1
    ): Boolean = coroutineScope {

        // 严格串行，避免并发手势
        moveMutex.withLock {

            // ====== 基础校验（只做一次）======
            if (startTime < 0 || duration < 0) {
                moveCallback?.onError()
                return@withLock false
            }

            val accService = service
                ?: run {
                    KeyguardUnLock.sendLog("无障碍服务未开启")
                    return@withLock false
                }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                KeyguardUnLock.sendLog("系统版本小于 7.0")
                return@withLock false
            }

            // ====== 构建路径（只构建一次）======
            val finalPathInfo = pathInfo ?: createNaturalSwipePathInfo(
                context = accService.applicationContext,
                useCurve = useCurve,
                curveIntensity = curveIntensity,
                horizontalOffsetRatio = horizontalOffsetRatio,
                jitterRatio = jitterRatio
            )

            val distancePx = kotlin.math.abs(finalPathInfo.startY - finalPathInfo.endY)
            val finalDuration = when {
                duration > 0L -> duration.coerceAtLeast(50L)
                autoDurationEnabled -> computeAutoDuration(
                    context = accService.applicationContext,
                    distancePx = distancePx,
                    curveIntensity = curveIntensity,
                    minMs = 80L,
                    maxMs = 900L
                )
                else -> 500L
            }

            // ====== 重试循环（总次数 = retryCount + 1）======
            repeat(retryCount + 1) { attempt ->

                if (attempt > 0) {
                    KeyguardUnLock.sendLog("手势失败，开始第 $attempt 次重试")
                    delay(200L)
                }

                // 给系统一点时间稳定（⚠️ 不在 Main）
                delay(60L)

                val gestureResult = withTimeoutOrNull(timeoutMs) {

                    withContext(Dispatchers.Main) {

                        suspendCancellableCoroutine<Boolean> { cont ->

                            val gesture = GestureDescription.Builder()
                                .addStroke(
                                    GestureDescription.StrokeDescription(
                                        finalPathInfo.path,
                                        startTime,
                                        finalDuration
                                    )
                                )
                                .build()

                            val callback =
                                if (moveCallback == null) null
                                else object : AccessibilityService.GestureResultCallback() {

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
                                showGestureIndicator(accService, finalPathInfo.path, finalDuration)
                                accService.dispatchGesture(gesture, callback, null)

                                // 没有 callback 的情况，直接认为已发出
                                if (callback == null && cont.isActive) {
                                    cont.resume(true)
                                }

                            } catch (t: Throwable) {
                                try { moveCallback?.onError() } catch (_: Throwable) {}
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                    }
                } ?: false // timeout：只代表“没等到回调”

                if (gestureResult) {
                    return@withLock true
                }
            }

            false
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
    @JvmOverloads
    @JvmStatic
    fun findAndPerformClickNodeInfo(
        service: AccessibilityService,
        id: String,
        text: String,
        contentDescription: String,
        isJava: Boolean = false
    ): Boolean {
        return performClickNodeInfo(service,findNodeInfo(service, id, text, contentDescription),true,isJava)
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
        if (isNew == 0 || isNew == 1){wakeKeyguardOn()}
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
    fun setSystemWeb(isSystem: Boolean = false) {
        MMKVUtil.put(MMKVConst.KEY_SYSTEMWEB,isSystem)
    }
    @JvmOverloads
    @JvmStatic
    fun getSystemWeb(default: Boolean = false): Boolean {
        return MMKVUtil.get(MMKVConst.KEY_SYSTEMWEB,default)
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
    fun setZQSuccess(zqs: Boolean = false) {
        MMKVUtil.put(MMKVConst.XPQ_ZQ_SUCCESS,zqs)
    }
    @JvmOverloads
    @JvmStatic
    fun getZQSuccess(default: Boolean = true): Boolean {
        return MMKVUtil.get(MMKVConst.XPQ_ZQ_SUCCESS,default)
    }

    @JvmOverloads
    @JvmStatic
    fun setAppLock(zqs: Boolean = false) {
        MMKVUtil.put(MMKVConst.KEY_APP_LOCK_ENABLED,zqs)
    }
    @JvmOverloads
    @JvmStatic
    fun getAppLock(default: Boolean = false): Boolean {
        return MMKVUtil.get(MMKVConst.KEY_APP_LOCK_ENABLED,default)
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

    @JvmOverloads
    @JvmStatic
    fun setAppPassWord(pwd: String = "1234") {
        MMKVUtil.put(MMKVConst.KEY_APP_PASSWORD,pwd)
    }
    @JvmOverloads
    @JvmStatic
    fun getAppPassWord(default: String = "1234"): String {
        return MMKVUtil.get(MMKVConst.KEY_APP_PASSWORD,default)
    }

    @JvmOverloads
    @JvmStatic
    fun setTanLog(tanlog: Boolean = false) {
        MMKVUtil.put(MMKVConst.XPQ_TAN_LOG,tanlog)
    }
    @JvmOverloads
    @JvmStatic
    fun getTanLog(default: Boolean = false): Boolean {
        return MMKVUtil.get(MMKVConst.XPQ_TAN_LOG,default)
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
    @JvmOverloads
    fun showClickIndicator(service: AccessibilityService? = accessibilityService, x: Int, y: Int) {
        service?: return
        val showguiji = MMKVUtil.get(MMKVConst.SHOW_DO_GUIJI, false)
        if (showguiji){
            val (screenWidth, screenHeight) = getScreenSize(service.applicationContext)
            //val baseOffset = 20
            val baseOffset = AssistsWindowManager.pxFromDp(service, AssistsWindowManager.DIAMETER_DP)/2
            // 计算偏移后的位置，确保不超出屏幕边界
            val adjustedX = when {
                x + baseOffset > screenWidth -> x - baseOffset
                else -> x + baseOffset
            }

            val adjustedY = when {
                y - baseOffset < 0 -> y + baseOffset
                else -> y - baseOffset
            }
            // 在主线程显示指示器
            Handler(Looper.getMainLooper()).post {
                ClickIndicatorManager.show(service, x, y)
                //ClickIndicatorManager.show(service, adjustedX, adjustedY)
            }
        }

    }

    @JvmStatic
    fun showGestureIndicator(service: AccessibilityService? = accessibilityService, path: Path, duration: Long) {
        service?: return
        val showguiji = MMKVUtil.get(MMKVConst.SHOW_DO_GUIJI, false)
        if (showguiji){
            // 在主线程显示指示器
            Handler(Looper.getMainLooper()).post {
                SwipeTrajectoryIndicatorManager.show(service, path, duration = 600L)

            }
        }

    }

    private fun tryActionClickWithParent(
        node: AccessibilityNodeInfo,
        maxDepth: Int = 5
    ): Boolean {

        var current: AccessibilityNodeInfo? = node
        var depth = 0

        while (current != null && depth <= maxDepth) {
            try {
                if (current.isClickable) {
                    if (current.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                    ) {
                        return true
                    }
                }
            } catch (_: Throwable) {
            }

            val parent = current.parent
            if (current !== node) {
                recycleSafe(current)
            }
            current = parent
            depth++
        }

        return false
    }

    @JvmOverloads
    @JvmStatic
    fun performClickNodeInfo(
        service: AccessibilityService? = accessibilityService,
        nodeInfo: AccessibilityNodeInfo?,
        isMoNi: Boolean = true,
        isJava: Boolean = false

    ): Boolean {

        if (service == null || nodeInfo == null) return false

        // 先拿坐标（Gesture 必须）
        val rect = Rect()
        try {
            nodeInfo.getBoundsInScreen(rect)
        } catch (_: Throwable) {
            recycleSafe(nodeInfo)
            return false
        }

        val hasValidRect = rect.isValid()
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        /**
         * 1️⃣ 强制只走 Gesture
         */
        if (isMoNi) {
            recycleSafe(nodeInfo)

            if (!hasValidRect) return false
            //if (service.rootInActiveWindow == null) return false

            sendLog("强制 Gesture 点击 (${centerX}, ${centerY})")

            val clicked = StableGestureClicker.click(
                service,
                centerX,
                centerY
            )

            return clicked
        }

        /**
         * 2️⃣ ACTION_CLICK（含 parent）
         */
        var actionClickSuccess = false
        if (false){
            //不含 parent
            actionClickSuccess = if (nodeInfo.isClickable) {
                try {
                    nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
        }else{
            // 含 parent
            actionClickSuccess = try {
                tryActionClickWithParent(nodeInfo)
            } catch (_: Throwable) {
                false
            }
        }

        recycleSafe(nodeInfo)

        if (actionClickSuccess) {
            sendLog("ACTION_CLICK（含 parent）成功")
            showClickIndicator(
                service,
                centerX,
                centerY
            )
            return true
        }

        /**
         * 3️⃣ Gesture 兜底
         */
        if (!hasValidRect) return false
        //if (service.rootInActiveWindow == null) return false

        sendLog("Gesture 兜底点击 (${centerX}, ${centerY})")

        val clicked = StableGestureClicker.click(
            service,
            centerX,
            centerY
        )


        return clicked
    }



     fun Rect.isValid(): Boolean =
        width() > 10 &&
                height() > 10 &&
                centerX() > 0 &&
                centerY() > 0

     fun recycleSafe(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                node.recycle()
            } catch (_: Throwable) {
            }
        }
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
        service: AccessibilityService? = accessibilityService,
        nodeInfo: AccessibilityNodeInfo?,
        isMoNi: Boolean = true,
        noParent: Boolean = true,
        maxDepth: Int = 5,
        interval: Long = 0L
    ): Boolean {
        if (service == null || nodeInfo == null) return false
        if (!canClick(interval)) return false
        // 先拿坐标（Gesture 必须）
        val rect = Rect()
        try {
            nodeInfo.getBoundsInScreen(rect)
        } catch (_: Throwable) {
            recycleSafe(nodeInfo)
            return false
        }

        val hasValidRect = rect.isValid()
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        /**
         * 1️⃣ 强制只走 Gesture
         */
        if (isMoNi) {
            recycleSafe(nodeInfo)

            if (!hasValidRect) return false
            //if (service.rootInActiveWindow == null) return false

            sendLog("强制 Gesture 点击 (${centerX}, ${centerY})")

            val clicked = StableGestureClicker.click(
                service,
                centerX,
                centerY
            )


            return clicked
        }

        /**
         * 2️⃣ ACTION_CLICK（含 parent）
         */
        var actionClickSuccess = false
        if (noParent){
            //不含 parent
            actionClickSuccess = if (nodeInfo.isClickable) {
                try {
                    nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
        }else{
            // 含 parent
            actionClickSuccess = try {
                tryActionClickWithParent(nodeInfo,maxDepth)
            } catch (_: Throwable) {
                false
            }
        }

        recycleSafe(nodeInfo)

        if (actionClickSuccess) {
            sendLog("ACTION_CLICK（含 parent）成功")
            showClickIndicator(
                service,
                centerX,
                centerY
            )
            return true
        }

        /**
         * 3️⃣ Gesture 兜底
         */
        if (!hasValidRect) return false
        //if (service.rootInActiveWindow == null) return false

        sendLog("Gesture 兜底点击 (${centerX}, ${centerY})")

        val clicked = StableGestureClicker.click(
            service,
            centerX,
            centerY
        )


        return clicked
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
    @JvmOverloads
    @JvmStatic
    fun moniClick(X: Int, Y: Int, service: AccessibilityService?, time: Long = MMKVConst.clickDu_Time): Boolean {
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
                val builder = GestureDescription.Builder().addStroke(StrokeDescription(path, 0, time))
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