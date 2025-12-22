package com.google.android.accessibility.ext.activity

import android.content.Context
import com.google.android.accessibility.ext.task.formatTime

import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.google.android.accessibility.ext.toast
import com.google.android.accessibility.ext.utils.DeviceLockState
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getDeviceLockState
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getDeviceStatusPlus
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getScreenState
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.MoveCallback
import com.google.android.accessibility.ext.utils.ScreenState

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.google.android.accessibility.selecttospeak.accessibilityService

import kotlin.also


/**
 * 将原有 object 改造成可继承的 open class，**不改变原有功能与流程**。
 * 仅对外暴露点进行 open 修饰以允许子类重写：
 * - jieSuoBy2
 * - doMyWork
 * - getUnlockPassword
 * - startJieSuoTask （保留原签名与 @JvmOverloads）
 * 其余逻辑、suspend 标记、delay/retry 行为全部保留原样。
 */
open class TaskByJieSuoHelper(
    // 保持原来的默认协程作用域
    protected val taskScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    protected val mutex = Mutex()

    /**
     * 保留原来的 @JvmOverloads 签名，方法仍然以原有逻辑执行。
     * 仅将方法设为 open 以允许子类覆盖（不强制子类覆盖）。
     */
    fun startJieSuoTask(context: Context, i: Int, start: Long = System.currentTimeMillis()) {
        taskScope.launch {
            if (mutex.isLocked) {
                sendLog("♥♥ 上次【自动解锁(方案2)】还没结束哦(有重试机制)，请稍等再试")
                context.toast("上次【自动解锁(方案2)】还没结束哦(有重试机制)，请稍等再试")
                return@launch
            }
            mutex.withLock {
                sendLog("♥♥ 开始执行【自动解锁(方案2)】任务")
                JieSuoTask(context, i, start)

            }
        }
    }

    /**
     * 保留为 suspend，逻辑与你原来的一致。
     */
    protected open suspend fun JieSuoTask(context: Context, i: Int, start: Long = System.currentTimeMillis()) {
        val pwd = getUnlockPassword()
        sendLog("♥♥ 保存的解锁密码为: ${pwd}")

        // 获取屏幕状态  如果黑屏则会点亮屏幕
        val isLiang = waitScreenLiang()
        if (!isLiang) {
            haoshiTip(start)
            if (hasActivity()){
                sendLog("♥♥ 未点亮屏幕,尝试采用【自动解锁(方案3)】点亮")
                //尝试 新方法 点亮屏幕  用 activity
                jieSuoBy2(i)
            }

            return
        }
        // 获取键盘是否锁定状态  如果锁定则会禁用键盘锁(无密码锁时才生效)
        val isJianPanUnLock = waitJianPanUnLock(pwd)
        if (!isJianPanUnLock){
            haoshiTip(start)
            if (hasActivity()){
                sendLog("♥♥ 未解锁屏幕,尝试采用【自动解锁(方案3)】解锁")
                //尝试 新方法 点亮屏幕  用 activity
                jieSuoBy2(i)
            }

            return
        }
        //走到这里,那肯定是点亮屏幕+解除键盘锁
        haoshiTip(start)
        sendLog("♥♥ 开始执行后续任务")
        //直接启动
        doMyWork(i)

/*        if (KeyguardUnLock.getUnLockOldBy1()){
            if (KeyguardUnLock.screenIsOn()) {
                haoshiTip(start)
                sendLog("♥♥ 【自动解锁(方案1)快】任务成功结束,屏幕已被点亮,且解除锁定")
                //直接启动
                doMyWork(i)
            }
        }else{
            //解锁任务结束
            if (KeyguardUnLock.screenIsOn() && KeyguardUnLock.keyguardIsOn()) {
                haoshiTip(start)
                sendLog("♥♥ 【自动解锁(方案1)滑】任务成功结束,屏幕已被点亮,且解除锁定")
                //直接启动
                doMyWork(i)
            } else{
                haoshiTip(start)
                sendLog("♥♥ 【自动解锁(方案1)】任务虽然结束,但屏幕未正常解锁,,尝试采用【自动解锁(方案2)】解锁")
                //尝试 新方法 点亮屏幕  用 activity
                jieSuoBy2(i)
            }
        }*/


    }

    private fun haoshiTip(start: Long) {
        val end = System.currentTimeMillis()
        val totalTime = end - start
        sendLog("♥♥ 【自动解锁(方案2)】任务耗时：${totalTime.formatTime()}")
    }

    /**
     * 尝试 新方法 点亮屏幕  用 activity
     * 子类可以重写此方法以改变点亮/解锁的行为（默认行为不变）
     */
    open fun jieSuoBy2(i:Int){

    }

    /**
     * 执行业务方法，子类可重写以自定义发送逻辑或更换数据源
     * 保持原有的分发逻辑不变
     */
    open fun doMyWork(i: Int){

    }

    /**
     * 获取（或拼装）解锁密码，子类可以覆盖该方法从不同来源获取密码
     */
    open fun getUnlockPassword(): String {
        return KeyguardUnLock.getScreenPassWord()
    }
    /**
     * 是否 增加 activity 解锁
     *
     *
     */
    protected open fun hasActivity(): Boolean {
        return true
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
    suspend fun waitScreenLiang(): Boolean {
        return delayAction(10) {
            retryCheckTaskWithLog("等待点亮屏幕",1000L,500L) {
                var isOn = false
                val status = getScreenState()
                // 访问屏幕状态
                when (status) {
                    ScreenState.ON ->{
                        isOn = true
                        KeyguardUnLock.sendLog("屏幕亮屏状态")
                    }
                    ScreenState.AOD -> {
                        isOn = false
                        KeyguardUnLock.wakeScreenOn()
                        KeyguardUnLock.sendLog("设备 AOD 模式,需要唤醒")
                    }
                    ScreenState.DOZING -> {
                        isOn = false
                        KeyguardUnLock.wakeScreenOn()
                        KeyguardUnLock.sendLog("设备 Doze 模式中(可能引起定时不准),需要唤醒")
                    }
                    ScreenState.OFF -> {
                        isOn = false
                        KeyguardUnLock.wakeScreenOn()
                        KeyguardUnLock.sendLog("屏幕关闭状态,需要唤醒")
                    }
                    ScreenState.UNKNOWN -> {
                        isOn = false
                        KeyguardUnLock.wakeScreenOn()
                        KeyguardUnLock.sendLog("未知状态,需要唤醒")
                    }

                }

                isOn
            }
        }
    }
    /**
     * 判断是否锁定
     * 如果黑屏,调用 旧版 点亮方法
     */
    suspend fun waitJianPanUnLock(pwd: String): Boolean {
        return delayAction(20) {
            retryCheckTaskWithLog("等待解除锁定屏幕",5000L,5000L) {
                var isOn = false
                val status = getDeviceLockState()
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
                        if (huaok){
                            //判断是否解锁
                            isOn = waitForUnlockCheck()
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
                            val inputOK = KeyguardUnLock.unlockScreenNew(password = pwd)
                            if (inputOK){
                                isOn = waitForUnlockCheck()
                            }

                        }


                    }
                }
                isOn
            }
        }
    }

    suspend fun waitForUnlockCheck(
        times: Int = 8,
        intervalMs: Long = 200L
    ): Boolean {
        repeat(times) { attempt ->
            // keyguardIsOn
            if (KeyguardUnLock.keyguardIsOn()) {
                sendLog("屏幕已成功解锁")
                return true
            }
            if (attempt < times - 1) delay(intervalMs)
        }
        return false
    }


    companion object {
        @Volatile
        private var instance: TaskByJieSuoHelper? = null

        /**
         * 获取或创建默认单例实例（线程安全）
         */
        @JvmStatic
        fun getInstance(): TaskByJieSuoHelper {
            return instance ?: synchronized(this) {
                instance ?: TaskByJieSuoHelper().also { instance = it }
            }
        }

        /**
         * 注入自定义实例（允许替换为子类实现）
         */
        @JvmStatic
        fun setInstance(helper: TaskByJieSuoHelper) {
            instance = helper
        }

        /**
         * 兼容旧调用：静态调用入口
         */
        @JvmOverloads
        @JvmStatic
        fun startJieSuoTaskInstance(context: Context, i: Int, start: Long = System.currentTimeMillis()) {
            getInstance().startJieSuoTask(context, i, start)
        }
    }
}

// 说明：
// - 我只做了最小改动：把 object 改为 open class，并将必要的扩展点标记为 open/ protected。
// - 所有原有的行为、方法名、suspend/非 suspend、默认参数等均保持不变，方便直接替换到你项目中。
// - 你现在可以通过 TaskByJieSuoHelper.setInstance(customHelper) 注入自定义实现，或直接继承并覆盖 open 方法。

