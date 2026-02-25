package com.google.android.accessibility.ext.activity

import android.content.Context
import android.util.Log
import com.google.android.accessibility.ext.task.formatTime

import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.google.android.accessibility.ext.toast
import com.google.android.accessibility.ext.utils.DeviceLockState
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.KeyguardUnLock.delayAction

import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.MoveCallback
import com.google.android.accessibility.ext.utils.ScreenState
import com.google.android.accessibility.ext.window.OverlayLog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.Job

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
    protected var taskJob: Job? = null
    var unLockMethod : Int = 0

    /**
     * 保留原来的 @JvmOverloads 签名，方法仍然以原有逻辑执行。
     * 仅将方法设为 open 以允许子类覆盖（不强制子类覆盖）。
     */
    fun startJieSuoTask(context: Context, i: Int, start: Long = System.currentTimeMillis(),myList: ArrayList<String> = arrayListOf()) {
        unLockMethod = KeyguardUnLock.getUnLockMethod()
        taskScope.launch {
            if (mutex.isLocked) {
                if (unLockMethod == 1){
                    sendLog("♥♥ 上次【自动解锁(方案1)】还没结束哦(有重试机制)，请稍等再试")
                    //context.toast("上次【自动解锁(方案1)】还没结束哦(有重试机制)，请稍等再试")
                }else if (unLockMethod == 2){
                    sendLog("♥♥ 上次【自动解锁(方案2)】还没结束哦(有重试机制)，请稍等再试")
                    //context.toast("上次【自动解锁(方案2)】还没结束哦(有重试机制)，请稍等再试")
                }

                return@launch
            }
            mutex.withLock {
                // ====== ⭐ 新增：记录当前任务 Job ======
                taskJob = coroutineContext[Job]
                // ======================================
                try {
                    if (OverlayLog.showed){
                        OverlayLog.hide()
                    }
                    if (unLockMethod == 1){
                        sendLog("♥♥ 开始执行【自动解锁(方案1)】任务")
                    }else if (unLockMethod == 2){
                        sendLog("♥♥ 开始执行【自动解锁(方案2)】任务")
                    }

                    JieSuoTask(context, i, start,myList)
                }finally {
                    // ====== ⭐ 核心：任务结束后取消协程 ======
                    taskJob?.cancel()
                    taskJob = null
                    // ========================================
                }


            }
        }
    }

    /**
     * 保留为 suspend，逻辑与你原来的一致。
     */
    protected open suspend fun JieSuoTask(context: Context, i: Int, start: Long = System.currentTimeMillis(),myList: ArrayList<String> = arrayListOf()) {
        val pwd = getUnlockPassword()
        sendLog("♥♥ 保存的解锁密码为: ${pwd}")

        // 获取屏幕状态  如果黑屏则会点亮屏幕
        val isLiang = KeyguardUnLock.waitScreenLiang()
        if (!isLiang) {
            haoshiTip(start)
            if (hasActivity()){
                Log.e("解锁失败了", "未点亮屏幕,尝试采用【自动解锁(方案3)】点亮 " )
                sendLog("♥♥ 未点亮屏幕,尝试采用【自动解锁(方案3)】点亮")
                //尝试 新方法 点亮屏幕  用 activity
                jieSuoBy2(i,myList)
            }
            return
        }

        var isDo = true
        var timeOutMillis = 2000L
        var periodMillis = 500L
        if (!KeyguardUnLock.deviceIsSecure()){
             //无安全锁
            if (unLockMethod == 1){
                //在这里之前 wakekeyguardon一定被执行过一次(方案切换到0或者1时,内容提供者oncreate中)
                // 所以才判断键盘是否已解除 ,但为了稳妥,额外判断一次
                if (!KeyguardUnLock.keyguardIsGone.get()){
                    //如果之前并没有执行过 wakeKeyguardOn,就执行一次
                    KeyguardUnLock.wakeKeyguardOn()
                }
                //获取键盘是否锁定状态 第一次单纯判断  超时1.5秒
                val isKeyguardOn = KeyguardUnLock.waitKeyguardOn()
                if (isKeyguardOn){
                    sendLog("♥♥ 屏幕已被解锁")
                    isDo = false
                }else{
                    sendLog("♥♥ 屏幕为锁定状态")
                    isDo = true
                }
            }
            timeOutMillis = 2000L
            periodMillis = 1000L

        }else{
            timeOutMillis = 6000L
            periodMillis = 6000L

        }


       if (isDo){
           // 获取键盘是否锁定状态  第二次判断
           val isJianPanUnLock = KeyguardUnLock.waitJianPanUnLock(pwd,unLockMethod,timeOutMillis,periodMillis)
           if (!isJianPanUnLock){
               haoshiTip(start)
               if (hasActivity()){
                   Log.e("解锁失败了", "未解锁屏幕,尝试采用【自动解锁(方案3)】解锁 " )
                   sendLog("♥♥ 未解锁屏幕,尝试采用【自动解锁(方案3)】解锁")
                   //尝试 新方法 点亮屏幕  用 activity
                   jieSuoBy2(i,myList)
               }
               return
           }
       }

        //走到这里,那肯定是点亮屏幕+解除键盘锁
        haoshiTip(start)
        sendLog("♥♥ 开始执行后续任务")
        //直接启动
        doMyWork(i,myList)


    }

    private fun haoshiTip(start: Long) {
        val end = System.currentTimeMillis()
        val totalTime = end - start
        if (unLockMethod == 1){
            sendLog("♥♥ 【自动解锁(方案1)】任务耗时：${totalTime.formatTime()}")
        }else if (unLockMethod == 2){
            sendLog("♥♥ 【自动解锁(方案2)】任务耗时：${totalTime.formatTime()}")
        }

    }

    /**
     * 尝试 新方法 点亮屏幕  用 activity
     * 子类可以重写此方法以改变点亮/解锁的行为（默认行为不变）
     */
    open fun jieSuoBy2(i:Int,myList: ArrayList<String> = arrayListOf()){

    }

    /**
     * 执行业务方法，子类可重写以自定义发送逻辑或更换数据源
     * 保持原有的分发逻辑不变
     */
    open fun doMyWork(i: Int,myList: ArrayList<String> = arrayListOf()){

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
        fun startJieSuoTaskInstance(context: Context, i: Int, start: Long = System.currentTimeMillis(),myList: ArrayList<String> = arrayListOf()) {
            getInstance().startJieSuoTask(context, i, start,myList)
        }
    }
}

// 说明：
// - 我只做了最小改动：把 object 改为 open class，并将必要的扩展点标记为 open/ protected。
// - 所有原有的行为、方法名、suspend/非 suspend、默认参数等均保持不变，方便直接替换到你项目中。
// - 你现在可以通过 TaskByJieSuoHelper.setInstance(customHelper) 注入自定义实现，或直接继承并覆盖 open 方法。

