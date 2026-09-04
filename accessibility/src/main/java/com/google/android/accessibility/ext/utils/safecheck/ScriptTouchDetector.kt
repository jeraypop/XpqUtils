package com.google.android.accessibility.ext.utils.safecheck

import android.util.Log
import kotlin.random.Random

/**
 * 本地最小版：调用栈判定机制，方法结构与微信 AccExptService 一一对应。
 *
 * 微信反编译原逻辑（AccExptService.java）：
 *   hasEvilConfig()          = evilStackList 非空 && randomPass(accInfoStrikeFactor)
 *   randomPass(percent)      = percent >= 100 || random(1,101) <= percent
 *   isEvilTraces(stack)      = hasEvilConfig() 门槛后，逐帧压成 "类名.方法名" 再匹配
 *   isEvilTraceStrings(list) = 双层子串（contains）匹配，命中任一特征即返回 true
 *   isEvilTraceNow()         = 取当前线程栈，直接喂给 isEvilTraces
 *
 * 本地最小版与微信的差异仅在「配置来源」：
 *   - evilStackList          → 本地硬编码常量（微信由服务端下发、; 分隔、可热更新）
 *   - accInfoStrikeFactor    → 本地 STRIKE_FACTOR 常量
 * 其余判定逻辑完全一致。
 */
object ScriptTouchDetector {

    /**
     * 命中百分比，对齐 accInfoStrikeFactor：0=关闭，100=必中，50≈半数概率。
     *
     * 本地调试建议设 100（特征命中即拦截，便于验证）；正式环境可降为概率值，
     * 达到微信"软干扰"效果——同一次脚本点击并非每次都拦，增加脚本方适配成本。
     */
    private const val STRIKE_FACTOR = 100

    /**
     * 本地硬编码的"恶意调用栈特征"，对齐 evilStackList。
     * 匹配用子串（contains），写包名前缀即可覆盖该引擎下所有类。
     *
     * 注意：明文写在此处反编译可见，生产应改为服务端下发 + 字符串混淆。
     */
    private val EVIL_STACK_LIST: List<String> = listOf(
        // 无障碍模拟点击（真人走 dispatchTouchEvent，链路里不会出现）
        "android.view.View.performAccessibilityAction",
        "android.view.accessibility.AccessibilityNodeInfo.performAction",
        // Google 无障碍扩展库（clickByText / click 等点击注入）
        "com.google.android.accessibility.ext.acc",
        // 触摸 / 输入事件注入 API
        "android.app.Instrumentation.sendPointerSync",
        "android.hardware.input.InputManager.injectInputEvent",
        // 已知自动点击 / 脚本引擎，按包名前缀一网打尽
        "org.autojs",
        "com.stardust",
    )

    /** 对齐 randomPass：percent>=100 必过，否则 random(1,101) <= percent。 */
    private fun randomPass(percent: Int): Boolean =
        percent >= 100 || Random.nextInt(1, 101) <= percent

    /** 对齐 hasEvilConfig：特征非空 && 概率命中，缺一不可。 */
    private fun hasEvilConfig(): Boolean =
        EVIL_STACK_LIST.isNotEmpty() && randomPass(STRIKE_FACTOR)

    /** 对齐 isEvilTraceStrings：栈帧 × 特征 双层子串匹配。 */
    private fun isEvilTraceStrings(frames: List<String>): Boolean {
        for (frame in frames) {
            for (mark in EVIL_STACK_LIST) {
                if (mark.isNotEmpty() && frame.contains(mark)) return true
            }
        }
        return false
    }

    /**
     * 对齐 isEvilTraces：吃「外部传入」的栈，先过概率门槛，再拼帧匹配。
     *
     * 用在触摸拦截时，把 `new Throwable().stackTrace` 拿到的完整注入链传进来，
     * 而不是每次重新抓当前栈——这样能覆盖到事件注入的源头帧。
     */
    fun isEvilTraces(stack: Array<StackTraceElement>): Boolean {
        if (!hasEvilConfig()) return false
        val frames = stack.map { it.className + "." + it.methodName }
        //Log.e("调用栈", "isEvilTraces: "+frames )
        return isEvilTraceStrings(frames)
    }

    /** 对齐 isEvilTraceNow：取当前线程栈做判定。 */
    fun isEvilTraceNow(): Boolean = isEvilTraces(Throwable().stackTrace)

    /** 兼容旧入口：当前线程是否疑似脚本点击。 */
    fun isScriptTrace(): Boolean = isEvilTraceNow()
}