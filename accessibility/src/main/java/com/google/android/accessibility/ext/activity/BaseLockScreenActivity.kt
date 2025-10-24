package com.google.android.accessibility.ext.activity

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.android.accessibility.ext.databinding.ActivityLockScreenBinding
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.DeviceLockState
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getDeviceStatusPlus
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.MMKVUtil
import com.google.android.accessibility.ext.utils.MoveCallback
import com.google.android.accessibility.ext.utils.ScreenState
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

        lifecycleScope.launch {
            try {
                sendLog("开始执行【自动解锁(方案2)】任务")
                val unlocked = showWhenLockedAndTurnScreenOn(this@BaseLockScreenActivity)
                if (unlocked){
                    onUnlockedAndProceed()
                }
                else {
                    sendLog("【自动解锁(方案2)】未成功或超时")
                }
            } catch (t: Throwable) {
                sendLog("【自动解锁(方案2)】执行出错")
            }finally {
                delay(5000L)
                sendLog("【自动解锁(方案2)】界面自动清理")
                finishAndRemoveTask()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        AliveUtils.toast(msg = "onNewIntent")
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { /* optional */ }
                sendLog("设备系统大于8.1  执行点亮屏幕")
                //判定 是否点亮
                if (!KeyguardUnLock.screenIsOn()){
                    //延时一会 再次判定 是否点亮
                    delay(500)
                    if (!KeyguardUnLock.screenIsOn()){
                        sendLog("屏幕依然黑屏,部分品牌机型上,请检查是否开启了,[后台弹出界面权限]" +
                                ", [允许在锁屏上显示]")
                        sendLog("尝试采取旧方法重新点亮(建议开启上述提到的 两个权限)")
                        KeyguardUnLock.wakeScreenOn()
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
                tryRequestDismissKeyguard(activity, timeoutMs)
            }
            DeviceLockState.LockedSecure -> {
                sendLog("设备被锁屏,设置了安全锁 [PIN、图案、密码、指纹、Face ID 等]")
                sendLog("准备呼出锁屏输入解锁密码界面")
                tryRequestDismissKeyguard(activity, timeoutMs)
            }
            else -> false
        }

        return lockResult
    }

    protected open suspend fun tryRequestDismissKeyguard(activity: Activity, timeoutMs: Long = 5000L): Boolean {



        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val resumed = AtomicBoolean(false)

                try {
                    val km = activity.getSystemService(KeyguardManager::class.java)
                    if (km == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        if (resumed.compareAndSet(false, true)) cont.resume(true)
                        return@suspendCancellableCoroutine
                    }

                    val cb = object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            if (resumed.compareAndSet(false, true)) {
                                sendLog("binggo 设备解锁成功")
                                cont.resume(true)
                            }
                        }

                        override fun onDismissCancelled() {
                            if (resumed.compareAndSet(false, true)) {
                                sendLog("解锁被取消")
                                cont.resume(false)
                            }
                        }

                        override fun onDismissError() {
                            if (resumed.compareAndSet(false, true)) {
                                sendLog("解锁出错")
                                cont.resume(false)
                            }
                        }
                    }

                    // 在非挂起上下文切回主线程触发系统解锁界面

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P){
                        try {
                            activity.mainExecutor.execute {
                                try {
                                    km.requestDismissKeyguard(activity, cb)
                                } catch (t: Throwable) {
                                    Log.w("BaseLockScreenActivity", "requestDismissKeyguard failed", t)
                                }
                            }
                        } catch (t: Throwable) {

                        }
                    }else{
                        activity.runOnUiThread {
                            try {
                                //2.呼出输入解锁密码界面
                                km.requestDismissKeyguard(activity, cb)
                            } catch (t: Throwable) {
                                Log.w("BaseLockScreenActivity", "requestDismissKeyguard failed", t)
                            }
                        }
                    }



                    // 如果协程被取消，避免继续尝试自动输入并尽量清理
                    cont.invokeOnCancellation { _ -> /* nothing to cleanup */ }

                    // 后备：延时 1s 后尝试自动输入密码（在 lifecycleScope 中运行，不阻塞当前 lambda）
                    lifecycleScope.launch {
                        try {

                            delay(1000)
                            if (resumed.get()) return@launch

                            if (KeyguardUnLock.deviceIsOn() && KeyguardUnLock.keyguardIsOn()){
                                sendLog("设备已解锁")
                                return@launch
                            }

                            //1.额外增加手势滑动,来呼出输入解锁密码界面
                            //2.requestDismissKeyguard(),也能呼出解锁密码界面
                            if (hasGesture()){
                                val ok = KeyguardUnLock.moveAwait(
                                    service = accessibilityService,
                                    moveCallback = object : MoveCallback {
                                        override fun onSuccess() {
                                            println("🟢 手势完成")
                                        }

                                        override fun onError() {
                                            println("🔴 手势取消或失败")
                                        }
                                    }

                                )
                                if (ok) {
                                    sendLog("上滑成功")
                                }
                                delay(500)
                                if (resumed.get()) return@launch
                            }

                            // 从子类提供的接口获取密码，子类可以覆盖 getUnlockPassword() 来改变自动输入的密码来源
                            val pwd = getUnlockPassword() ?: ""
                            if (pwd.isEmpty()) {
                                sendLog("未配置自动解锁密码，跳过自动输入")
                                return@launch
                            }
                            val unlockSuccess = withContext(Dispatchers.IO) {
                                try {
                                    KeyguardUnLock.unlockScreenNew(password = pwd)
                                } catch (t: Throwable) {
                                    Log.w("BaseLockScreenActivity", "unlockScreenNew failed", t)
                                    false
                                }
                            }

                            if (unlockSuccess) {
                                if (resumed.compareAndSet(false, true)) {
                                    sendLog("自动输入密码完毕,解锁成功")
                                    cont.resume(true)
                                }
                            } else {
                                sendLog("自动输入密码失败,解锁失败")
                            }

                        } catch (t: Throwable) {
                            Log.w("BaseLockScreenActivity", "auto-unlock task failed", t)
                        }
                    }

                } catch (e: Throwable) {
                    if (resumed.compareAndSet(false, true)) {
                        cont.resumeWithException(e)
                    }
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

        lifecycleScope.launch {
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
        return try {
            val p = MMKVUtil.get(MMKVConst.KEY_LOCK_SCREEN_PASSWORD, "")
            if (p.isNullOrEmpty()) "" else p
        } catch (t: Throwable) {
            Log.w("BaseLockScreenActivity", "getUnlockPassword failed", t)
            ""
        }
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
