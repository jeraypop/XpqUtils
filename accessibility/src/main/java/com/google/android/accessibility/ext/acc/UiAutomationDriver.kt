package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.ext.utils.LibCtxProvider
import com.google.android.accessibility.ext.utils.gestureUtils.HumanTouchEngine.gaussian
import com.google.android.accessibility.uiautomation.engine.InvisibleAutomation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * UiAutomation 通道：包装移植自 accessibilityLibs 的 [InvisibleAutomation]。
 * 免开无障碍，用 Shizuku shell 身份注册 registerUiTestAutomationService。
 */
object UiAutomationDriver : AccDriver {

    override val mode get() = EngineMode.UIAUTOMATION
    override val isConnected get() = InvisibleAutomation.isConnected

    /** 最近一次连接的失败原因（简短），连接成功时为空。 */
    val lastError: String? get() = InvisibleAutomation.lastError

    /**
     * UiAutomation 模式输入文本的底层策略，测试后可收敛定稿。
     * [InputTextStrategy.CLIPBOARD_PASTE] 支持中文，[InputTextStrategy.SHELL_INPUT_TEXT] 仅 ASCII。
     */
    @Volatile
    var inputTextStrategy: InputTextStrategy = InputTextStrategy.CLIPBOARD_PASTE

    /** UiAutomation 输入文本在后台协程执行（shell RPC + sleep 均为阻塞操作，严禁占用主线程）。 */
    private val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun connect(onLog: (String) -> Unit): Boolean {
        val ctx = runCatching { LibCtxProvider.Companion.appContext }.getOrNull()
        if (ctx == null) {
            onLog("✗ UiAutomation 连接失败：appContext 未初始化")
            return false
        }
        return InvisibleAutomation.connect(ctx, 15000L, onLog)
    }

    override fun disconnect() = InvisibleAutomation.disconnect()

    /** 探测 system_server 是否已有 UiAutomation 注册（被其它 App/进程占用）。 */
    fun isUiAutomationOccupied(): Boolean = InvisibleAutomation.isUiAutomationOccupied()

    override fun rootInActiveWindow(): AccessibilityNodeInfo? = InvisibleAutomation.getRoot()
    override fun windows(): List<AccessibilityWindowInfo>? = InvisibleAutomation.getWindows()
    override fun findFocus(focusType: Int): AccessibilityNodeInfo? = InvisibleAutomation.findFocus(focusType)

    override fun performGlobalAction(action: Int): Boolean = InvisibleAutomation.performGlobalAction(action)

    override fun dispatchGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?
    ): Boolean {
        val (points, duration) = gestureToPoints(gesture)
        if (points.isEmpty()) {
            Log.e("调用栈", "points: 为空", )
            callback?.onCancelled(gesture)
            return false
        }
        val ok = InvisibleAutomation.dispatchGesture(points, maxOf(1L, duration))
        if (ok) callback?.onCompleted(gesture) else callback?.onCancelled(gesture)
        return ok
    }

    override fun inputText(node: AccessibilityNodeInfo?, text: String): Boolean =
        inputTextAsync(node, text, null)

    override fun inputTextPaste(node: AccessibilityNodeInfo?, byClipboard: Boolean, text: String): Boolean =
        // 粘贴语义固定走剪贴板方案；byClipboard 为无障碍通道的历史遗留参数，此处忽略
        inputTextAsync(node, text, InputTextStrategy.CLIPBOARD_PASTE)

    override fun inputTextNew(node: AccessibilityNodeInfo?, text: String): Boolean =
        inputTextAsync(node, text, null)

    /** 三个输入入口共用的异步实现：点击聚焦 → 延迟 → 按策略注入。[strategy] 为 null 时走 [inputTextStrategy]。 */
    private fun inputTextAsync(node: AccessibilityNodeInfo?, text: String, strategy: InputTextStrategy?): Boolean {
        if (node == null) {
            Log.e("调用栈", "inputText: node 为空")
            return false
        }

        // 先在当前调用线程取出输入框中心坐标（node 随后可能被系统回收，不宜再在后台线程访问）
        val rect = Rect()
        node.getBoundsInScreen(rect)
        var cx = rect.centerX().toFloat()
        var cy = rect.centerY().toFloat()
        cx = (cx + gaussian(std = 0.5, maxOffset = 2.0)).toFloat().coerceAtLeast(0f)
        cy = (cy + gaussian(std = 0.5, maxOffset = 2.0)).toFloat().coerceAtLeast(0f)
        val effective = strategy ?: inputTextStrategy
        Log.e("调用栈", "inputText: 点击输入框中心 ($cx, $cy) 策略=$effective text=$text")

        // shell `input` 命令为跨进程阻塞 RPC，且需 sleep 等待 IME 焦点；若在主线程同步执行必然 ANR。
        // 故整体下沉到后台协程，方法立即返回 true 表示已提交任务。
        inputScope.launch {
            // 1️⃣ 真实点击输入框中心唤起 IME 焦点（shell tap，与点击通道一致）
            if (!InvisibleAutomation.tap(cx, cy)) {
                Log.e("调用栈", "inputText: 聚焦点击失败（mSvc 是否为空 / shell 不可用）")
                return@launch
            }
            // 等待 IME 焦点到位（点击后输入法弹出需要时间）
            delay(300L+Random.nextLong(200))

            // 2️⃣ 按策略注入文本
            when (effective) {
                InputTextStrategy.SHELL_INPUT_TEXT -> {
                    Log.e("调用栈", "inputText: 走 shell input text")
                    InvisibleAutomation.shellInputText(text)
                }

                InputTextStrategy.CLIPBOARD_PASTE -> {
                    val ctx = runCatching { LibCtxProvider.Companion.appContext }.getOrNull()
                    if (ctx == null) {
                        Log.e("调用栈", "inputText: appContext 为空")
                        return@launch
                    }
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (cm == null) {
                        Log.e("调用栈", "inputText: ClipboardManager 为空")
                        return@launch
                    }
                    cm.setPrimaryClip(ClipData.newPlainText("label", text))
                    Log.e("调用栈", "inputText: 已写剪贴板，执行 shell 粘贴")
                    InvisibleAutomation.shellPaste()
                }
            }
        }
        return true
    }

    override fun setOnAccessibilityEventListener(listener: ((AccessibilityEvent) -> Unit)?) {
        InvisibleAutomation.setOnAccessibilityEventListener(listener)
    }

    /** GestureDescription → 轨迹点列表（PathMeasure 采样 path），返回 (轨迹点, 时长ms)。 */
    private fun gestureToPoints(g: GestureDescription): Pair<List<PointF>, Long> {
        val points = mutableListOf<PointF>()
        var duration = 0L
        for (i in 0 until g.strokeCount) {
            val stroke = g.getStroke(i)
            // 滑动时长只取 stroke.duration（startTime 是延迟启动时间，input swipe 无延迟概念，
            // 若把 startTime 也累加会导致滑动时长翻倍、速度减半，上划解锁等快速 fling 场景失败）。
            duration = maxOf(duration, stroke.duration)
            val pm = PathMeasure(stroke.path, false)
            val len = pm.length
            if (len <= 0f) {
                // 点击手势 path 可能为单点/零长度（microMoves 微移全命中同点，如 randomHit 落回起点），
                // PathMeasure 此时返回 0，且 getPosTan(0) 对零长度 path 会错误返回 (0,0)。
                // 改用 computeBounds 取包围盒中心（moveTo + lineTo 到同点仍会计入 bounds），正确定位点击坐标。
                val bounds = RectF()
                stroke.path.computeBounds(bounds, false)
                points.add(PointF(bounds.centerX(), bounds.centerY()))
                continue
            }
            val steps = (stroke.duration / 10L).toInt().coerceIn(2, 40)
            for (j in 0..steps) {
                val pos = FloatArray(2)
                pm.getPosTan(len * j / steps, pos, null)
                points.add(PointF(pos[0], pos[1]))
            }
        }
        return points to duration
    }
}
