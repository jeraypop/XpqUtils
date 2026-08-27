package com.google.android.accessibility.uiautomation.shizuku

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.uiautomation.util.HiddenApi
import java.io.BufferedReader
import java.io.InputStreamReader

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
