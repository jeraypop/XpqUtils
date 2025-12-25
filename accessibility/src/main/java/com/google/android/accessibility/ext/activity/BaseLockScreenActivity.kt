package com.google.android.accessibility.ext.activity

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import com.android.accessibility.ext.databinding.ActivityLockScreenBinding
import com.google.android.accessibility.ext.task.formatTime
import com.google.android.accessibility.ext.utils.DeviceLockState
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getDeviceStatusPlus
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.MoveCallback
import com.google.android.accessibility.ext.utils.ScreenState
import com.google.android.accessibility.ext.window.OverlayLog
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 可直接复制使用的基类：BaseLockScreenActivity
 * 包名：com.example.lockscreenlib
 * 使用说明：子类继承 BaseLockScreenActivity 即可复用所有点亮/唤醒/解锁/自动输入密码的逻辑。
 * 子类可以通过覆盖 getUnlockPassword() 提供要自动输入的锁屏密码；如果不覆盖，默认从缓存读取。
 * 启动方式：
 *   BaseLockScreenActivity.openLockScreenActivity(context, YourActivity::class.java, index, list)
 * 或者直接使用子类的类对象：
 *   BaseLockScreenActivity.openLockScreenActivity(context, LockScreenActivitySend::class.java, index, list)
 */
@Suppress("MemberVisibilityCanBePrivate")
open class BaseLockScreenActivity : XpqBaseActivity<ActivityLockScreenBinding>(
    bindingInflater = ActivityLockScreenBinding::inflate
) {

    companion object {
        /**
         * 要启动的 Activity class
         */
        @JvmOverloads
        @JvmStatic
        fun openBaseLockScreenActivity(context: Context = appContext, cls: Class<out Activity>, i: Int) {
            val intent = Intent(context, cls)
            intent.putExtra(MMKVConst.SEND_MSG_INDEX, i)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }

    // --- intent data ---
    protected var index: Int = 1

    // --- debounce 控制 ---
    private var lastClickTime = 0L
    private val debounceInterval = 3000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.android.accessibility.ext.R.layout.activity_lock_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(com.android.accessibility.ext.R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        handleIntent(intent)
        //最小化activity 1像素
        val window = window
        window.setGravity(Gravity.START or Gravity.TOP)
        val params = window.attributes
        params.width = 1
        params.height = 1
        params.x = 0
        params.y = 0
        window.attributes = params
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        KeyguardUnLock.appScope.launch {
            val start = System.currentTimeMillis()
            try {
                sendLog("开始执行【自动解锁(方案3)】任务")

                val unlocked = showWhenLockedAndTurnScreenOn(this@BaseLockScreenActivity)
                val end = System.currentTimeMillis()
                val totalTime = end - start
                sendLog("♥♥ 【自动解锁(方案3)】任务耗时：${totalTime.formatTime()}")
                if (unlocked){
                    onUnlockedAndProceed()
                }
                else {
                    sendLog("【自动解锁(方案3)】未成功或超时")
                    val end = System.currentTimeMillis()
                    val totalTime = end - start
                    sendLog("♥♥ 【自动解锁(方案3)】任务耗时：${totalTime.formatTime()}")
                    if (KeyguardUnLock.getTanLog()) OverlayLog.show()
                }
            } catch (t: Throwable) {
                sendLog("【自动解锁(方案3)】执行出错")
                if (KeyguardUnLock.getTanLog()) OverlayLog.show()
            }finally {
                delay(5000L)
                sendLog("【自动解锁(方案3)】界面自动清理")
                finishAndRemoveTask()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        sendLog("onNewIntent")
        setIntent(intent)
        handleIntent(intent)
    }

    protected open fun handleIntent(intent: Intent) {
        index = intent.getIntExtra(MMKVConst.SEND_MSG_INDEX, 1)
    }

    override fun onResume() { super.onResume() }
    override fun onStop() { super.onStop() }
    override fun onDestroy() { super.onDestroy() }

    override fun initView_Xpq() {}
    override fun initData_Xpq() {}

    // ---------------------
    // 可复用的核心方法（与原实现保持一致）
    // ---------------------

    /**
     * 点亮并在锁屏上显示，通常用于 Android O MR1 及以上
     */
    protected open suspend fun showWhenLockedAndTurnScreenOn(activity: Activity, timeoutMs: Long = 10000L): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            withContext(Dispatchers.Main) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                // 如果需要保持屏幕不灭
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { /* optional */ }
                sendLog("设备系统大于8.1  执行点亮屏幕")
                //判定 是否点亮
                if (!waitForScreenOnCheck(5,200)){
                    sendLog("屏幕依然黑屏,部分品牌机型上,请检查是否开启了,[后台弹出界面权限]" +
                            ", [允许在锁屏上显示]")
                    sendLog("尝试采取旧方法重新点亮(建议开启上述提到的 两个权限)")
                    KeyguardUnLock.wakeScreenOn()
                }
                // ⭐ 核心：下一帧立刻释放 Activity Window
                window.decorView.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        sendLog("锁屏界面已点亮，立即销毁 Activity 以释放 Window")
                        activity.finishAndRemoveTask()
                    }
                }

            }
            requestDeviceUnlock(activity, timeoutMs)
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.Main) {
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
                sendLog("设备系统小于8.1  执行点亮屏幕+解锁")
            }
            true
        }

    }

    suspend fun waitForScreenOnCheck(
        times: Int = 8,
        intervalMs: Long = 200L
    ): Boolean {
        repeat(times) { attempt ->
            if (KeyguardUnLock.screenIsOn()) {
                sendLog("屏幕已亮屏")
                return true
            }
            if (attempt < times - 1) delay(intervalMs)
        }
        return false
    }
    suspend fun waitForKeyguardOnCheck(
        times: Int = 8,
        intervalMs: Long = 200L
    ): Boolean {
        repeat(times) { attempt ->
            if (KeyguardUnLock.keyguardIsOn()) {
                sendLog("键盘已解锁")
                return true
            }
            if (attempt < times - 1) delay(intervalMs)
        }
        return false
    }

    protected open suspend fun requestDeviceUnlock(activity: Activity, timeoutMs: Long = 5000L): Boolean {
        val status = getDeviceStatusPlus()
        when (status.screenState) {
            ScreenState.ON -> {
                sendLog("屏幕亮屏状态")
            }
            ScreenState.AOD -> {
                KeyguardUnLock.wakeScreenOn()
                sendLog("设备 AOD 模式,需要唤醒")
                sendLog("部分品牌机型上,请检查是否开启了,[后台弹出界面权限], [允许在锁屏上显示]")
            }
            ScreenState.DOZING -> {
                KeyguardUnLock.wakeScreenOn()
                sendLog("设备 Doze 模式中(可能引起定时不准),需要唤醒")
                sendLog("部分品牌机型上,请检查是否开启了,[后台弹出界面权限], [允许在锁屏上显示]")
            }
            ScreenState.OFF -> {
                KeyguardUnLock.wakeScreenOn()
                sendLog("屏幕关闭状态,需要唤醒")
                sendLog("部分品牌机型上,请检查是否开启了,[后台弹出界面权限], [允许在锁屏上显示]")
            }
            ScreenState.UNKNOWN -> {
                KeyguardUnLock.wakeScreenOn()
                sendLog("未知状态,需要唤醒")
                sendLog("部分品牌机型上,请检查是否开启了,[后台弹出界面权限], [允许在锁屏上显示]")
            }
        }

        val lockResult = when (val lockState = status.lockState) {
            is DeviceLockState.Unlocked -> {
                val msg = if (lockState.isDeviceSecure) "设备已被解锁（有安全锁）,即将执行后续操作" else "设备已被解锁（无安全锁）,即将执行后续操作"
                sendLog(msg)
                true
            }
            DeviceLockState.LockedNotSecure -> {
                sendLog("设备被锁屏,未设置安全锁,[可能是 滑动解锁或无锁屏]")
                sendLog("准备直接解锁")
                tryRequestDismissKeyguard(activity,false, timeoutMs)
            }
            DeviceLockState.LockedSecure -> {
                sendLog("设备被锁屏,设置了安全锁 [PIN、图案、密码、指纹、Face ID 等]")
                sendLog("准备呼出锁屏输入解锁密码界面")
                tryRequestDismissKeyguard(activity,true, timeoutMs)
            }
            else -> false
        }

        return lockResult
    }

    protected open suspend fun tryRequestDismissKeyguard(activity: Activity, doInput: Boolean, timeoutMs: Long = 5000L): Boolean {
        //delay(1000)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val resumed = AtomicBoolean(false)
                val attemptStarted = AtomicBoolean(false) // 防止重复尝试上划/自动解锁

                // 封装：执行上划 + （可选）自动输入密码 的补救流程
                fun attemptGestureAndAutoUnlockOnce() {
                    if (!attemptStarted.compareAndSet(false, true)) {
                        // 已经开始尝试一次，忽略后续重复触发
                        sendLog("手势:已经开始尝试一次上划，忽略后续重复触发")
                        return
                    }

                    KeyguardUnLock.appScope.launch {
                        try {
                            // 先检查是否已经被其他路径解锁
                            if (resumed.get()) return@launch
                            // 如果设备已解锁，直接 resume true（防御）
                            if (waitForKeyguardOnCheck()) {
                                sendLog("手势: 设备已解锁（尝试前检测），直接结束")
                                if (resumed.compareAndSet(false, true)) cont.resume(true)
                                return@launch
                            }else{
                                sendLog("手势: 设备未解锁,准备尝试上划")
                            }
                            // ⭐ 新增：上划前延迟 2 秒，确保 Activity 已销毁 & 系统稳定
                            sendLog("手势: 等待 2 秒，确保 Activity 已销毁")
                            delay(2000)

                            if (resumed.get()) return@launch
                            // 1) 如果支持手势则执行上划以尝试呼出输入框或直接解锁（对于 LockedNotSecure/similar 场景）
                            if (hasGesture()) {
                                sendLog("手势: 开始执行上划手势（补救）")
                                val ok = try {
                                    KeyguardUnLock.moveAwait(
                                        service = accessibilityService,
                                        moveCallback = object : MoveCallback {
                                            override fun onSuccess() { /* log in callback if needed */ }
                                            override fun onError() { /* log in callback if needed */ }
                                        }
                                    )
                                } catch (t: Throwable) {
                                    Log.w("BaseLockScreenActivity", "attempt: moveAwait failed", t)
                                    false
                                }

                                if (ok) {
                                    sendLog("手势: 上滑手势成功")
                                } else {
                                    sendLog("手势: 上滑手势失败或被取消")
                                }

                                // 给系统一点时间渲染（不同机型差异较大）
                                delay(300)
                                if (resumed.get()) return@launch
                            } else {
                                sendLog("手势:: 设备不支持手势或 hasGesture() 返回 false，跳过上划")
                            }

                            // 2) 若 doInput == true, 再尝试自动输入密码；如果 doInput == false 则在此结束（返回 false）
                            if (!doInput) {
                                sendLog("手势: 没有锁屏密码，结束后续（直接返回成功）")
                                if (resumed.compareAndSet(false, true)) cont.resume(true)
                                return@launch
                            }

                            // 获取密码（子类覆盖 getUnlockPassword()）
                            val pwd = try { getUnlockPassword() ?: "" } catch (t: Throwable) {
                                Log.w("BaseLockScreenActivity", "attempt: getUnlockPassword threw", t)
                                ""
                            }
                            if (pwd.isEmpty()) {
                                sendLog("手势: 设备有锁屏密码(但软件中未设置)，无法执行自动输入，结束尝试（返回失败）")
                                if (resumed.compareAndSet(false, true)) cont.resume(false)
                                return@launch
                            }

                            // 3) 在IO线程尝试解锁（自动输入）
                            val unlockSuccess = withContext(Dispatchers.IO) {
                                try {
                                    delay(500)
                                    KeyguardUnLock.inputPassword(password = pwd)
                                } catch (t: Throwable) {
                                    Log.w("BaseLockScreenActivity", "attempt: unlockScreenNew failed", t)
                                    false
                                }
                            }

                            if (unlockSuccess) {
                                sendLog("手势: 自动输入密码成功，设备解锁")
                                if (resumed.compareAndSet(false, true)) cont.resume(true)
                            } else {
                                sendLog("手势: 自动输入密码失败，设备仍未解锁")
                                if (resumed.compareAndSet(false, true)) cont.resume(false)
                            }
                        } catch (t: Throwable) {
                            Log.w("BaseLockScreenActivity", "attemptGestureAndAutoUnlockOnce failed", t)
                            if (resumed.compareAndSet(false, true)) cont.resume(false)
                        }
                    }
                }

                try {
                    val km = activity.getSystemService(KeyguardManager::class.java)
                    if (km == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        // 低版本或无法获取 KeyguardManager，认为无须等待系统回调
                        if (resumed.compareAndSet(false, true)) cont.resume(true)
                        return@suspendCancellableCoroutine
                    }

                    val cb = object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            if (resumed.compareAndSet(false, true)) {
                                sendLog("onDismissSucceeded: 设备解锁成功")
                                cont.resume(true)
                            }
                        }

                        override fun onDismissCancelled() {
                            // 注意：不要在这里直接 resume(false)，而是触发补救流程
                            sendLog("onDismissCancelled: 系统返回解锁取消，触发补救上划/自动输入流程")
                            // 触发一次补救流程（不会重复）
                            attemptGestureAndAutoUnlockOnce()
                        }

                        override fun onDismissError() {
                            // 同上：触发补救流程
                            sendLog("onDismissError: 系统返回解锁出错，触发补救上划/自动输入流程")
                            attemptGestureAndAutoUnlockOnce()
                        }
                    }

                    // 在主线程触发系统解锁界面（兼容 mainExecutor / runOnUiThread）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            activity.mainExecutor.execute {
                                try {
                                    km.requestDismissKeyguard(activity, cb)
                                } catch (t: Throwable) {
                                    Log.w("BaseLockScreenActivity", "requestDismissKeyguard failed", t)
                                    // 如果请求出错，直接触发补救流程
                                    attemptGestureAndAutoUnlockOnce()
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w("BaseLockScreenActivity", "dispatch to mainExecutor failed", t)
                            attemptGestureAndAutoUnlockOnce()
                        }
                    } else {
                        activity.runOnUiThread {
                            try {
                                km.requestDismissKeyguard(activity, cb)
                            } catch (t: Throwable) {
                                Log.w("BaseLockScreenActivity", "requestDismissKeyguard failed", t)
                                attemptGestureAndAutoUnlockOnce()
                            }
                        }
                    }

                    // 在协程被取消时不做额外清理（attempt 内部受 attemptStarted 控制）
                    cont.invokeOnCancellation { _ -> /* nothing to cleanup */ }

                    // 原来的后备入口：延时后尝试（仅在尚未由回调触发 attempt 时执行）
                    KeyguardUnLock.appScope.launch {
                        try {
                            delay(1000)
                            if (resumed.get()) return@launch

                            // 如果 keyguard 已不在，可能已经被解锁
                            if (waitForKeyguardOnCheck()) {
                                sendLog("后备检查：设备已解锁（无需补救）")
                                if (resumed.compareAndSet(false, true)) cont.resume(true)
                                return@launch
                            }

                            // 还未开始补救，则由这里触发一次
                            attemptGestureAndAutoUnlockOnce()
                        } catch (t: Throwable) {
                            Log.w("BaseLockScreenActivity", "backup auto-unlock task failed", t)
                        }
                    }

                } catch (e: Throwable) {
                    if (resumed.compareAndSet(false, true)) cont.resumeWithException(e)
                }
            }
        }

        return result ?: false
    }

    /**
     * 子类可以覆盖 onUnlockedAndProceed 做自定义逻辑。
     * 默认行为是防抖后延迟 3s，调用 doMyWork
     * 如果 子类 覆盖此方法 不会再防抖
     */
    protected open fun onUnlockedAndProceed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < debounceInterval) return
        lastClickTime = currentTime

        KeyguardUnLock.appScope.launch {
            doMyWork(index)
        }
    }

    /**
     * 子类可以覆盖以实现发送行为或其它操作
     * 如果 子类 覆盖此方法 有防抖功能
     */
    protected open suspend fun doMyWork(i: Int) {

    }

    /**
     * 获取要用于自动解锁的密码。子类可以覆盖此方法以提供自定义密码获取方式（例如从安全存储或运行时输入）。
     * 默认实现：从 CacheUtil 中读取 KEY_LOCK_SCREEN_PASSWORDSEND。
     * 返回 null 或空字符串表示不尝试自动输入密码。
     */
    protected open fun getUnlockPassword(): String? {
        return KeyguardUnLock.getScreenPassWord()
    }

    /**
     * 是否 增加 模拟手势上划
     *
     *
     */
    protected open fun hasGesture(): Boolean {
        return false
    }

}
