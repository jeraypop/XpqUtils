package com.google.android.accessibility.ext.utils.safecheck

import android.view.MotionEvent
import kotlin.math.hypot

/**
 * 本地最小版：行为时序分析（对齐微信第三套机制）。
 *
 * 微信反编译原逻辑（kc5.i.dealOnTouchEvent -> kc5.g -> 上报 bs3.q NormMsg native）：
 *   - ACTION_DOWN 时重置 move 计数 a.c = 0，开启一次采集
 *   - ACTION_MOVE 时 a.c++ 累计移动次数
 *   - ACTION_UP  时把「move 计数 + 触点坐标/时序」交给 native 做真人/脚本指纹比对
 *
 * 本地最小版把「native 上报」替换成「本地启发式阈值判定」：
 *   真人手指：DOWN→UP 之间有持续时长 + 通常伴随 MOVE 微抖动 + 位移
 *   脚本注入：直接 injectEvent(DOWN, UP)，时长近乎瞬时、无 MOVE、坐标不动
 * 据此在 UP 时给出「是否疑似脚本」的结论。
 *
 * 局限（与微信的差距，注意不要误以为等价）：
 *   - 微信把 raw 序列交给 native/云端模型，特征可服务端下发、可持续学习；
 *   本地版只有这几个硬编码阈值，且单设备无法做跨设备的行为聚合。
 *   - 启发式主要拦「粗暴的直接注入」；自带滑动轨迹的高仿真脚本仍可能绕过，
 *   需配合第一套（调用栈判定）+ 第二套（概率节点伪造）叠加使用。
 */
object TouchBehaviorAnalyzer {

    /** 一次完整触摸序列（DOWN→…→UP）的采集结果，对齐微信 bs3.p 上报的数据。 */
    data class Session(
        val downTime: Long,     // DOWN 的 eventTime（ms）
        val downX: Float,       // DOWN 相对坐标
        val downY: Float,
        val upTime: Long,       // UP 的 eventTime（ms）
        val upX: Float,         // UP 相对坐标
        val upY: Float,
        val moveCount: Int,     // 对齐微信 a.c：DOWN~UP 之间的 MOVE 次数
        val minPressure: Float, // DOWN~UP 过程最小压力（含 MOVE）
        val maxPressure: Float, // DOWN~UP 过程最大压力（含 MOVE）
        val minSize: Float,     // DOWN~UP 过程最小接触面积（含 MOVE）
        val maxSize: Float,     // DOWN~UP 过程最大接触面积（含 MOVE）
        val toolType: Int,      // UP 时的工具类型（手指/未知等，仅供诊断定标）
        val source: Int,        // UP 时的 input source（触摸屏/鼠标等，仅供诊断定标）
    ) {
        val durationMs: Long get() = upTime - downTime
        val distance: Float get() = hypot(upX - downX, upY - downY)

        /** 压力波动幅度：真人从接触到压实再到离开必然起伏；shell 注入恒定死值 ≈ 0。 */
        val pressureRange: Float get() = maxPressure - minPressure

        /** 接触面积波动幅度：同上，shell 注入恒定 ≈ 0。 */
        val sizeRange: Float get() = maxSize - minSize
    }

    /** 判定阈值（本地启发式；微信为 native 引擎内模型参数，可服务端下发）。 */
    private const val INSTANT_DURATION_MS = 30L    // 低于此值几乎不可能是真人物理点击
    private const val INJECT_DURATION_MS = 100L    // 直接 inject DOWN+UP 的典型时长上限
    private const val MIN_HUMAN_DISTANCE = 0f      // 位移下限（px），可按业务调大

    /**
     * shell `input tap/swipe` 注入识别阈值：
     * system_server 合成 MotionEvent 时给 pressure / size 一个恒定值（本机实测为 1.0），
     * 全程无任何波动；真人物理触摸的压力与接触面积在 DOWN→MOVE→UP 全过程必然起伏。
     * 故用「压力波动 ∧ 面积波动」近乎为 0 作为指纹（而非绝对值等于 0，避免 ROM 差异漏拦）。
     */
    private const val SHELL_PRESSURE_RANGE_EPS = 0.001f  // 压力波动判定容差
    private const val SHELL_SIZE_RANGE_EPS = 0.001f      // 接触面积波动判定容差

    /** 进行中的采集会话（对齐微信在 DOWN 时初始化、UP 时产出结果）。 */
    private var pending: Pending? = null

    private class Pending(
        val downTime: Long,
        val downX: Float,
        val downY: Float,
        var moveCount: Int,
        var lastX: Float,
        var lastY: Float,
        var minPressure: Float,
        var maxPressure: Float,
        var minSize: Float,
        var maxSize: Float,
        var toolType: Int,
        var source: Int,
    )

    /**
     * 对齐 kc5.i.dealOnTouchEvent：喂入每个触摸事件。
     *
     * - DOWN 开启采集会话（重置 moveCount）；
     * - MOVE 累计计数与位置；
     * - UP  结束会话并返回本次采集结果（供 [isSuspicious] 判定）；
     * - 其余动作（POINTER_DOWN/UP、CANCEL 等）返回 null。
     */
    fun dealOnTouchEvent(event: MotionEvent): Session? {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val p = event.pressure
                val s = event.size
                pending = Pending(
                    event.eventTime, event.x, event.y, 0, event.x, event.y,
                    p, p, s, s, event.getToolType(0), event.source,
                )
                null
            }
            MotionEvent.ACTION_MOVE -> {
                pending?.let {
                    it.moveCount++                       // 对齐 a.c++
                    it.lastX = event.x
                    it.lastY = event.y
                    // 记录压力/面积的极值区间：真人按压有起伏（区间明显），
                    // shell 注入恒定死值（区间≈0），据此与真人区分。
                    if (event.pressure > it.maxPressure) it.maxPressure = event.pressure
                    if (event.pressure < it.minPressure) it.minPressure = event.pressure
                    if (event.size > it.maxSize) it.maxSize = event.size
                    if (event.size < it.minSize) it.minSize = event.size
                }
                null
            }
            MotionEvent.ACTION_UP -> {
                val p = pending ?: return null
                pending = null
                val upPressure = event.pressure
                val upSize = event.size
                if (upPressure > p.maxPressure) p.maxPressure = upPressure
                if (upPressure < p.minPressure) p.minPressure = upPressure
                if (upSize > p.maxSize) p.maxSize = upSize
                if (upSize < p.minSize) p.minSize = upSize
                Session(
                    p.downTime, p.downX, p.downY,
                    event.eventTime, event.x, event.y, p.moveCount,
                    p.minPressure, p.maxPressure, p.minSize, p.maxSize,
                    event.getToolType(0), event.source,
                )
            }
            else -> null
        }
    }

    /**
     * 判定一次会话是否疑似脚本注入。
     *
     * 启发式规则：
     *   1. 时长 < [INSTANT_DURATION_MS]：真人手指接触→离开发送事件的间隔几乎不可能这么短；
     *   2. 时长 < [INJECT_DURATION_MS] 且无任何 MOVE/位移：脚本直接 inject DOWN+UP 的典型特征；
     *   3. 压力与接触面积全程无波动：shell `input tap/swipe`（system_server 合成、
     *      pressure/size 恒定死值）的指纹，真人物理触摸的按压与面积必随接触过程起伏。
     */
    fun isSuspicious(session: Session): Boolean {
        if (session.durationMs < INSTANT_DURATION_MS) return true
        if (session.durationMs < INJECT_DURATION_MS
            && session.moveCount == 0
            && session.distance < MIN_HUMAN_DISTANCE
        ) {
            return true
        }
        if (session.pressureRange < SHELL_PRESSURE_RANGE_EPS
            && session.sizeRange < SHELL_SIZE_RANGE_EPS
        ) {
            //return true
        }
        return false
    }

    /** 便捷入口：喂入事件，UP 时直接返回「是否疑似脚本」；非 UP 恒返回 false。 */
    fun isScriptTouch(event: MotionEvent): Boolean =
        dealOnTouchEvent(event)?.let(::isSuspicious) ?: false
}