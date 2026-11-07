package com.google.android.accessibility.ext.vdisplay

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.google.android.accessibility.inputmethod.KeepAliveInputMethod
import com.google.android.accessibility.uiautomation.engine.InvisibleAutomation

/**
 * 虚拟屏上的输入法输入：临时把库内输入法切成当前输入法，直接用 InputConnection 提交文本，
 * 结束后恢复用户原来的输入法。
 *
 * 与 shell `input text`（仅 ASCII）和剪贴板粘贴相比，不经过剪贴板、不产生按键事件，
 * 中文/任意字符都可用，更像真实输入。仅 Android 14+ 可用：低版本虚拟屏没有独立焦点，
 * IME 会落回主屏，直接返回 false 让调用方走原有通道。
 */
internal object VDisplayImeInput {
    private const val TAG = "XpqVDisplayIme"
    private const val CONNECT_TIMEOUT_MS = 1_500L
    private const val POLL_MS = 100L
    private const val FOCUS_SETTLE_MS = 300L

    /**
     * 该机型副屏接不到输入法时的记忆。
     *
     * 部分 ROM（如联想 ZUI）虽然建了独立焦点副屏，系统仍判定 IME 窗口在主屏：
     * `isInputMethodClientFocus: display ID mismatch. from client: 2 from window: 0`，
     * 于是 `showSoftInput` 被忽略、`InputConnection` 永远拿不到。命中一次后不再重试，
     * 避免每次输入都白等超时；换机型/重启进程会自动重新尝试。
     */
    @Volatile
    private var unsupported = false

    /** 是否已判定本机副屏不支持输入法输入。 */
    @JvmStatic
    fun isUnsupported(): Boolean = unsupported

    /**
     * 在 [displayId] 上给 [packageName] 的编辑框输入 [text]。
     * [focusX]/[focusY] 是输入框中心坐标：切换输入法前先点中它，让这块屏的应用成为当前输入法客户端。
     * [replace] 为 true 时清空原内容再输入，否则在光标处插入。
     *
     * 成功返回 true；不支持（低版本/无 Shizuku/IME 未就绪/本机不支持副屏 IME）返回 false，
     * 调用方应回退其它输入方式。
     */
    fun type(
        context: Context,
        displayId: Int,
        packageName: String,
        text: String,
        replace: Boolean,
        focusX: Int? = null,
        focusY: Int? = null,
    ): Boolean {
        if (unsupported) return false
        if (displayId <= 0 || packageName.isBlank()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val imeId = runCatching { KeepAliveInputMethod.xpqImeId }.getOrNull() ?: return false
        // 少数 ROM 会对读取默认输入法抛 SecurityException，拿不到就放弃，交给剪贴板回退
        val original = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
        val alreadyEnabled = runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList?.any { it.id == imeId } == true
        }.getOrDefault(false)

        KeepAliveInputMethod.beginSession(context, original)
        KeepAliveInputMethod.setHeadless(true)
        var selected = false
        var restored = false
        try {
            // 顺序很关键：先点中编辑框，让副屏上的应用成为「当前输入法客户端」，
            // 系统绑定输入法时会按当前客户端所在屏决定输入法窗口挂哪块屏。
            // 若先切输入法，当前客户端还在主屏，输入法窗口就会绑到主屏、副屏永远拿不到连接。
            if (focusX != null && focusY != null) {
                XpqVDisplay.injectTap(displayId, focusX, focusY)
                SystemClock.sleep(FOCUS_SETTLE_MS)
            }
            if (!alreadyEnabled && !shell("ime enable $imeId")) return false
            if (!shell("ime set $imeId")) return false
            selected = true
            if (!awaitConnection(displayId, packageName, CONNECT_TIMEOUT_MS / 2)) {
                // 切换输入法可能让编辑框失焦：再点一次并继续等连接
                if (focusX != null && focusY != null) {
                    XpqVDisplay.injectTap(displayId, focusX, focusY)
                }
                if (!awaitConnection(displayId, packageName, CONNECT_TIMEOUT_MS)) {
                    Log.w(
                        TAG,
                        "IME connection timeout display=$displayId pkg=$packageName; " +
                            KeepAliveInputMethod.debugConnectionState(displayId, packageName)
                    )
                    // 输入法已切过去仍拿不到连接，说明这块屏接不到 IME，后续直接走剪贴板
                    unsupported = true
                    return false
                }
            }
            if (!KeepAliveInputMethod.isReadyFor(displayId, packageName)) {
                Log.w(TAG, "IME not on target display=$displayId pkg=$packageName")
                return false
            }
            val ok = KeepAliveInputMethod.commitOnDisplay(displayId, packageName, text, replace)
            Log.i(TAG, "ime input display=$displayId pkg=$packageName replace=$replace ok=$ok")
            return ok
        } catch (t: Throwable) {
            Log.w(TAG, "ime input failed display=$displayId pkg=$packageName", t)
            return false
        } finally {
            KeepAliveInputMethod.setHeadless(false)
            if (selected) restored = shell("ime set $original")
            if (!alreadyEnabled && restored) shell("ime disable $imeId")
            KeepAliveInputMethod.endSession(context, restored || !selected)
        }
    }

    private fun awaitConnection(displayId: Int, packageName: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (KeepAliveInputMethod.isReadyFor(displayId, packageName)) return true
            SystemClock.sleep(POLL_MS)
        }
        return KeepAliveInputMethod.isReadyFor(displayId, packageName)
    }

    /** 以 shell 身份执行；Shizuku 不可用时返回 false。 */
    private fun shell(command: String): Boolean = runCatching {
        InvisibleAutomation.exec(command)?.exitCode == 0
    }.getOrDefault(false).also {
        if (!it) Log.w(TAG, "shell failed: $command")
    }
}
