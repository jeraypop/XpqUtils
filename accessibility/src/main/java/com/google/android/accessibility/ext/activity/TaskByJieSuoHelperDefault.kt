package com.google.android.accessibility.ext.activity

import android.content.Context
import android.text.TextUtils
import com.google.android.accessibility.ext.task.formatTime

import com.google.android.accessibility.ext.utils.KeyguardUnLock

import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.MoveCallback
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
open class TaskByJieSuoHelperDefault(
    // 保持原来的默认协程作用域
    protected val taskScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    protected val mutex = Mutex()
    protected var taskJob: Job? = null


    /**
     * 保留原来的 @JvmOverloads 签名，方法仍然以原有逻辑执行。
     * 仅将方法设为 open 以允许子类覆盖（不强制子类覆盖）。
     */
    fun startJieSuoTask(context: Context, i: Int, start: Long = System.currentTimeMillis(),myList: ArrayList<String> = arrayListOf()) {
        taskScope.launch {
            if (mutex.isLocked) {
                sendLog("♥♥ 上次【自动解锁(方案0)】还没结束哦(有重试机制)，请稍等再试")
                //context.toast("上次【自动解锁(方案1)】还没结束哦(有重试机制)，请稍等再试")
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

                    sendLog("♥♥ 开始执行【自动解锁(方案0)】任务")
                    JieSuoTask(context, i, start,myList)

                } finally {

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
        var isJieSuo = false
        // 获取屏幕状态  如果黑屏则会点亮屏幕
        KeyguardUnLock.waitScreenLiang()
        //禁用键盘锁
        if (!KeyguardUnLock.deviceIsSecure()||TextUtils.isEmpty(pwd)){
            sendLog("设备未设置安全密码锁,或者解锁密码未设置")
            //滑动锁屏
            //因为如果是滑动解锁的话,调用disablekeyguad后,结果将不再准确
            //所以我们就不再判断键盘是否锁了
            //在这里之前 wakekeyguardon一定被执行过一次(方案切换到0或者1时,内容提供者oncreate中)
            // 所以才判断键盘是否已解除 ,但为了稳妥,额外判断一次
            if (!KeyguardUnLock.keyguardIsGone.get()){
                //如果之前并没有执行过 wakeKeyguardOn,就执行一次
                KeyguardUnLock.wakeKeyguardOn()
            }
            //第一次判断 键盘锁
            //理论上 waitKeyguardOn 值就是true
            if (KeyguardUnLock.waitKeyguardOn()){
                sendLog("♥♥ 屏幕已被解锁")
                isJieSuo = true
            }else{
                sendLog("♥♥ 屏幕未被解锁,准备上划解锁")
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
                    //第二次判断 键盘锁
                    if (KeyguardUnLock.waitKeyguardOn()){
                        sendLog("♥♥ 屏幕终于被解锁")
                        isJieSuo = true
                    }else{
                        sendLog("♥♥ 屏幕依然未被解锁")
                    }
                }else{
                    sendLog("屏幕上划失败")
                }

            }

        }else{
            //pin锁屏
            sendLog("设备设置了安全密码锁,开始滑动并输入密码")
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
                if (inputOK){
                    //输入密码成功
                    //第一次判断  键盘锁
                    if (KeyguardUnLock.waitKeyguardOn()){
                        sendLog("♥♥ 屏幕已被解锁")
                        isJieSuo = true
                    }else{
                        sendLog("♥♥ 屏幕未被解锁")
                    }
                }else{
                    //输入密码失败
                }
            }else{
                sendLog("屏幕上划失败")
            }
        }

        //
        //走到这里,那肯定是点亮屏幕+解除键盘锁
        haoshiTip(start)
        if (isJieSuo){
            sendLog("屏幕解锁成功,继续执行后续任务")
        }else{
            sendLog("屏幕解锁失败,仍然继续执行后续任务(这就可能会额外引起不必要的耗电,建议先使用另外3种解锁方案中的一种,另外3种都不行,再使用此方案0)")
            //return
        }
        //直接启动
        doMyWork(i,myList)


    }

    private fun haoshiTip(start: Long) {
        val end = System.currentTimeMillis()
        val totalTime = end - start
        sendLog("♥♥ 【自动解锁(方案0)】任务耗时：${totalTime.formatTime()}")
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
        private var instance: TaskByJieSuoHelperDefault? = null

        /**
         * 获取或创建默认单例实例（线程安全）
         */
        @JvmStatic
        fun getInstance(): TaskByJieSuoHelperDefault {
            return instance ?: synchronized(this) {
                instance ?: TaskByJieSuoHelperDefault().also { instance = it }
            }
        }

        /**
         * 注入自定义实例（允许替换为子类实现）
         */
        @JvmStatic
        fun setInstance(helper: TaskByJieSuoHelperDefault) {
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

