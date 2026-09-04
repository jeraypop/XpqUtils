package com.google.android.accessibility.uiautomation.shizuku

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.uiautomation.util.HiddenApi
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 以 shell(uid 2000) 身份运行的自动化特权服务。
 *
 * 由 Shizuku 启动，因此具备：
 *  - 调用隐藏 API AccessibilityManager.registerUiTestAutomationService 的权限；
 *  - 注入输入事件、执行 input 命令等能力。
 *
 * App 进程持有 UiAutomation，并通过本服务完成"注册 / 反注册"等特权动作。
 */
class AutomationUserService : IAutomationUserService.Stub {

    private val tag = "AutomationUserService"

    @Suppress("unused")
    constructor(context: Context) : super() {
        Log.i(tag, "UserService(Context) created")
    }

    constructor() : super() {
        Log.i(tag, "UserService() created")
    }

    /** 记录当前已注册的 client，便于反注册。 */
    @Volatile
    private var registeredClient: IBinder? = null

    /** 记录当前已注册的 AccessibilityServiceInfo flags / owner，便于调试日志。 */
    @Volatile
    private var registeredToken: IBinder? = null

    override fun exec(command: String?): ShellResult {
        val result = ShellResult()
        if (command.isNullOrEmpty()) {
            result.exitCode = -1
            result.stderr = "empty command"
            return result
        }
        try {
            val process = ProcessBuilder("sh", "-c", command).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread { readStream(process.inputStream, out) }
            val errThread = Thread { readStream(process.errorStream, err) }
            outThread.start()
            errThread.start()
            result.exitCode = process.waitFor()
            outThread.join()
            errThread.join()
            process.destroy()
            result.stdout = out.toString()
            result.stderr = err.toString()
        } catch (e: Exception) {
            result.exitCode = -1
            result.stderr = Log.getStackTraceString(e)
        }
        return result
    }

    /**
     * 以 shell 身份反射 InputManager.injectInputEvent 注入一次点击。
     *
     * 与 `input tap` 的本质区别：这里用 MotionEvent 完整重载【自定 pressure/size/toolType】，
     * 能伪造「DOWN 0.8 → UP 0.6」的真人式压力起伏，用于验证反注入启发式是否被绕过。
     * （shell `input` 命令由 system_server 合成，pressure 恒为固定值，可被「波动≈0」识别。）
     */
    override fun injectTap(
        x: Float,
        y: Float,
        durationMs: Long,
        pressureDown: Float,
        pressureUp: Float,
        sizeDown: Float,
        sizeUp: Float
    ): Boolean {
        return try {
            HiddenApi.ensure()
            // 实验：伪造 deviceId / isVirtual —— 选用真实物理触摸屏 id 填入注入事件
            val fakeDeviceId = resolvePhysicalDeviceId()
            val downTime = SystemClock.uptimeMillis()

            val props = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = pressureDown
                size = sizeDown
            }

            // 反射 @hide：InputManager.getInstance() + injectInputEvent()
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getMethod("getInstance").invoke(null)
            val inject = imClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )

            val down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN,
                1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, fakeDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            inject.invoke(im, down, 0)
            down.recycle()

            // 6~17 个 MOVE：围绕起点做 1~3px 微颤（非随机游走，位移受控），pressure/size 线性渐变
            val moves = Random.nextInt(6, 18)
            // 一次点击固定一个微颤半径（0.5~1.5px），故总位移落在 1~3px，不会像随机游走那样越走越远
            val shakeRadius = Random.nextDouble(0.5, 1.5).toFloat()
            val phase0 = Random.nextDouble(0.0, 2.0 * PI)
            // 用非整数相位增量，让微颤轨迹不是规整闭合圆，避免机器可识别的周期性
            val phaseStepX = 2.0 * PI * Random.nextDouble(1.3, 2.7) / moves
            val phaseStepY = 2.0 * PI * Random.nextDouble(1.3, 2.7) / moves
            var prevTime = downTime
            for (i in 1..moves) {
                val t = i.toFloat() / (moves + 1)
                // 严格单调递增的时间戳：整数除法可能截断致相邻相等，这里保证每次至少 +1ms
                val eventTime = maxOf(
                    downTime + (durationMs * i) / (moves + 1),
                    prevTime + 1
                )
                // 事件时间不能快于真实时钟（否则 injectInputEvent 会 drop）
                val realGap = SystemClock.uptimeMillis() - downTime
                if (eventTime > prevTime && eventTime - downTime > realGap) {
                    SystemClock.sleep(eventTime - downTime - realGap)
                }
                // 受控微颤：坐标始终围绕 (x, y)，半径受限，位移不会累积
                coords.x = x + (sin(phase0 + phaseStepX * i) * shakeRadius).toFloat()
                coords.y = y + (cos(phase0 + phaseStepY * i) * shakeRadius).toFloat()
                coords.pressure = pressureDown + (pressureUp - pressureDown) * t
                coords.size = sizeDown + (sizeUp - sizeDown) * t
                val move = MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_MOVE,
                    1, arrayOf(props), arrayOf(coords),
                    0, 0, 1f, 1f, fakeDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
                inject.invoke(im, move, 0)
                move.recycle()
                prevTime = eventTime
            }

            // UP：终点回到起点附近（微颤半径内），避免 UP 与 MOVE 跳变
            val upTime = maxOf(downTime + durationMs, prevTime + 1)
            val realGap = SystemClock.uptimeMillis() - downTime
            if (upTime - downTime > realGap) {
                SystemClock.sleep(upTime - downTime - realGap)
            }
            coords.pressure = pressureUp
            coords.size = sizeUp
            val up = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP,
                1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, fakeDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            inject.invoke(im, up, 0)
            up.recycle()
            true
        } catch (t: Throwable) {
            Log.e(tag, "injectTap 失败: ${Log.getStackTraceString(t)}")
            false
        }
    }

    /**
     * 实验：枚举当前真实物理触摸屏设备 id，用于测试注入事件的 deviceId / isVirtual 是否可伪造。
     * 找不到时回退 0（保持原样）。
     */
    private fun resolvePhysicalDeviceId(): Int {
        return try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val im = imClass.getMethod("getInstance").invoke(null)

            val ids = imClass
                .getMethod("getInputDeviceIds")
                .invoke(im) as IntArray

            val getDevice = imClass.getMethod(
                "getInputDevice",
                Int::class.javaPrimitiveType
            )

            var firstPhysicalTouchscreenId: Int? = null

            Log.i(tag, "========== InputDevice 列表 ==========")

            for (id in ids) {
                val dev = getDevice.invoke(im, id) as? InputDevice ?: continue

                val isTouchscreen =
                    (dev.sources and InputDevice.SOURCE_TOUCHSCREEN) != 0

                Log.i(
                    tag,
                    """ 实验：
                deviceId=${dev.id}
                name=${dev.name}
                descriptor=${dev.descriptor}
                isVirtual=${dev.isVirtual}
                isEnabled=${dev.isEnabled}
                sources=0x${dev.sources.toString(16)}
                isTouchscreen=$isTouchscreen
                vendorId=${dev.vendorId}
                productId=${dev.productId}
                controllerNumber=${dev.controllerNumber}
                keyboardType=${dev.keyboardType}
                motionRangeCount=${dev.motionRanges.size}
                """.trimIndent()
                )

                // 先记录第一个符合条件的设备，但不要立即 return
                if (!dev.isVirtual && isTouchscreen) {
                    if (firstPhysicalTouchscreenId == null) {
                        firstPhysicalTouchscreenId = dev.id
                    }
                }
            }

            Log.i(tag, "========== InputDevice 列表结束 ==========")

            val result = firstPhysicalTouchscreenId

            if (result != null) {
                Log.i(
                    tag,
                    "实验：找到物理触摸设备，暂时选择第一个 deviceId=$result"
                )
                result
            } else {
                Log.w(
                    tag,
                    "实验：未找到物理触摸设备，回退 deviceId=0"
                )
                0
            }

        } catch (t: Throwable) {
            Log.e(
                tag,
                "实验：枚举设备失败 ${Log.getStackTraceString(t)}，回退 deviceId=0"
            )
            0
        }
    }

    override fun registerUiAutomation(token: IBinder?, client: IBinder?, flags: Int) {
        if (client == null) throw IllegalArgumentException("client cannot be null")
        if (token == null) throw IllegalArgumentException("token cannot be null")
        try {
            HiddenApi.ensure()
            val automationToolFlag = HiddenApi.FLAG_IS_AUTOMATION_TOOL
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                packageNames = null
                feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                notificationTimeout = 100
                this.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        automationToolFlag
            }
            val userId = HiddenApi.currentUserId()
            val iam = HiddenApi.getIAccessibilityManager()

            // 把 IBinder 还原为 IAccessibilityServiceClient 接口（运行期类型）
            val clientInterface = Class.forName("android.accessibilityservice.IAccessibilityServiceClient")
            val clientAsInterface = clientInterface.getMethod("asInterface", IBinder::class.java)
                .invoke(null, client)

            Log.i(
                tag,
                "准备注册：info.flags=0x${java.lang.Integer.toHexString(info.flags)} " +
                        "automationTool=0x${java.lang.Integer.toHexString(automationToolFlag)} " +
                        "userId=$userId connectFlags=$flags"
            )

            val accepted = HiddenApi.registerUiTestAutomationService(
                iam = iam,
                owner = token,
                client = clientAsInterface!!,
                info = info,
                flags = flags,
                userId = userId
            )
            registeredClient = client
            registeredToken = token
            Log.i(tag, "registerUiAutomation accepted=$accepted client=$client")
            if (!accepted) {
                // 注册被 system_server 拒绝会让 framework 永远等不到 init 回调，最终 5 秒超时。
                // 提前把真实原因扔回 App，让 UI 立刻可见；可选原因：
                //   - FLAG_IS_AUTOMATION_TOOL 缺失或 ROM 不识别（部分定制 ROM 强制要求其它位）
                //   - currentUserId 不匹配
                //   - AccessibilityAutomationService 已被其它自动化占用
                throw RuntimeException(
                    "system_server 拒绝注册（registerUiTestAutomationService 返回非 true）。" +
                            " 常见原因：info.flags 缺 FLAG_IS_AUTOMATION_TOOL(${
                                java.lang.Integer.toHexString(automationToolFlag)
                            })、userId 不匹配，或当前已有其它 UiAutomation 在占用。"
                )
            }
        } catch (e: Throwable) {
            Log.e(tag, "registerUiAutomation FAILED", e)
            throw e
        }
    }

    override fun unregisterUiAutomation(client: IBinder?) {
        val target = client ?: registeredClient ?: return
        try {
            val iam = HiddenApi.getIAccessibilityManager()
            val clientInterface = Class.forName("android.accessibilityservice.IAccessibilityServiceClient")
            val clientAsInterface = clientInterface.getMethod("asInterface", IBinder::class.java)
                .invoke(null, target)
            HiddenApi.unregisterUiTestAutomationService(iam, clientAsInterface!!)
            if (registeredClient == target) {
                registeredClient = null
                registeredToken = null
            }
            Log.i(tag, "unregisterUiAutomation done")
        } catch (e: Exception) {
            Log.w(tag, "unregister failed: ${Log.getStackTraceString(e)}")
        }
    }

    override fun destroy() {
        Log.i(tag, "destroy()")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun exit() {
        destroy()
    }

    private fun readStream(input: java.io.InputStream, sb: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(input)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
            }
        } catch (e: Exception) {
            sb.append(e)
        }
    }
}
