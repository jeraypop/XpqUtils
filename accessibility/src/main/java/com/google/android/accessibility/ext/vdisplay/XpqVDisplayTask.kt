package com.google.android.accessibility.ext.vdisplay

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.accessibility.ext.acc.XpqAcc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 虚拟屏任务会话管理：建屏、把目标 App 启动到副屏、切换 Xpq 节点来源、恢复/释放。
 *
 * 宿主只需要决定「用哪个用户启动」（机主 0 / 分身 userId），其余流程都走这里：
 * ```
 * val ok = XpqVDisplayTask.prepare(context, "com.tencent.mm", targetUserId)
 * if (ok) {
 *     // 任务结束后
 *     XpqVDisplayTask.restore()
 * }
 * ```
 * 屏幕和 App 会保留到下次任务，[release] 才会真正释放。
 */
object XpqVDisplayTask {
    private const val TAG = "XpqVDisplayTask"

    @Volatile
    private var session: XpqVDisplay.Session? = null

    @Volatile
    private var driverInstalled = false

    @Volatile
    var displayId: Int = -1
        private set

    /** 当前任务启动 App 使用的用户：0=机主空间，非 0=分身空间。 */
    @Volatile
    var userId: Int = 0
        private set

    /** 最近一次 [prepare] 失败的原因；成功时清空，供宿主写入任务日志。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 当前是否已经把操作切到虚拟屏（屏建好且 Xpq 已换成副屏 driver）。 */
    val active: Boolean get() = session != null && displayId >= 0 && driverInstalled

    /** 当前会话，供调试/自定义取帧用；没有时为 null。 */
    fun currentSession(): XpqVDisplay.Session? = session

    /**
     * 准备虚拟屏并把 [packageName] 启动到屏上。
     * Shizuku 没运行/没授权/建屏失败/主屏正在使用该 App 时返回 false，调用方回退到原来的主屏流程。
     *
     * @param targetUserId 启动用户：0=机主空间，非 0=分身空间（可先用 [XpqVDisplay.dualUserId] 自动识别）。
     */
    suspend fun prepare(
        context: Context,
        packageName: String,
        targetUserId: Int = 0,
    ): Boolean = withContext(Dispatchers.Main) {
        lastError = null
        if (!VDisplaySettings.isEnabled(context)) {
            Log.i(TAG, "virtual display disabled by switch, using main screen")
            lastError = "虚拟屏开关已关闭"
            release()
            return@withContext false
        }
        // 虚拟屏依赖 Shizuku（Android 11+）；低版本直接走主屏，不做任何尝试
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "virtual display needs Android 11+, using main screen")
            lastError = "系统版本低于 Android 11，无法使用虚拟屏"
            return@withContext false
        }
        if (!XpqVDisplay.isReady()) {
            Log.w(TAG, "Shizuku not ready")
            lastError = "Shizuku 未运行或未授权"
            return@withContext false
        }
        // UiAutomation 模式没连上时，先尝试连接一次；无障碍模式这条一般是空操作
        if (!XpqAcc.isConnected) {
            withContext(Dispatchers.IO) { runCatching { XpqAcc.connect() } }
                .onFailure { Log.w(TAG, "XpqAcc.connect failed", it) }
        }
        // 每次任务都用全新副屏：先结束旧屏上的任务，再释放，避免残留、错版本或停在非首页
        release()
        val made = XpqVDisplay.create(context)?.also { session = it }
            ?: run {
                Log.w(TAG, "create virtual display failed")
                lastError = XpqVDisplay.lastCreateError ?: "创建虚拟屏失败"
                return@withContext false
            }
        displayId = made.displayId
        userId = targetUserId

        val launchCode = made.launchInUser(packageName, targetUserId)
        if (launchCode != XpqVDisplay.LAUNCH_OK) {
            // 已经在副屏上的「同一个包 + 同一个用户」可以接着用；别的版本、别的 App 都不算
            val task = made.topTaskId()
            val reusable = task >= 0 && made.displayOfTask(task) == made.displayId &&
                made.packageOfTask(task) == packageName && made.userIdOfTask(task) == targetUserId
            if (!reusable) {
                val reason = when (launchCode) {
                    XpqVDisplay.LAUNCH_IN_USE_ON_MAIN -> "主屏正在使用该应用，无法移到副屏"
                    XpqVDisplay.LAUNCH_NOT_FOUND -> "该用户里没有这个应用的启动入口（可能未安装）"
                    XpqVDisplay.LAUNCH_USER_MISMATCH -> "系统没有在分身空间启动应用，实际打开了另一个版本"
                    else -> "目标应用无法在虚拟屏启动"
                }
                Log.w(TAG, "$packageName cannot start on virtual display user $targetUserId, code=$launchCode")
                // 拉起了另一个版本:把这块屏放掉，免得下次又复用错的那份
                if (launchCode == XpqVDisplay.LAUNCH_USER_MISMATCH) release()
                lastError = reason
                return@withContext false
            }
        }
        if (!XpqAcc.useDisplay(made.displayId)) {
            Log.w(TAG, "XpqAcc.useDisplay failed")
            lastError = "切换节点来源失败"
            return@withContext false
        }
        driverInstalled = true
        if (VDisplaySettings.isPreviewEnabled(context)) XpqVDisplayPreview.show(context, made)
        true
    }

    /** 在当前用户下把 [packageName] 重新启动到虚拟屏（桌面入口 + CLEAR_TOP）。 */
    fun launch(packageName: String): Boolean =
        session?.launchInUser(packageName, userId) == XpqVDisplay.LAUNCH_OK

    /** 在虚拟屏上按返回键。 */
    fun back(): Boolean = session?.back() ?: false

    /** 把 Xpq 换回主屏（虚拟屏留着，下次任务直接复用）。 */
    fun restore() {
        driverInstalled = false
        XpqAcc.clearDisplay()
    }

    /**
     * 释放虚拟屏并隐藏预览。释放前先结束屏上的任务：
     * 直接释放的话，系统可能把任务挪回主屏，突然弹在用户正在用的屏幕上。
     */
    fun release() {
        restore()
        XpqVDisplayPreview.hide()
        session?.let { s ->
            if (!runCatching { s.endTasks() }.getOrDefault(false)) {
                Log.w(TAG, "tasks may remain on released display")
            }
            s.release()
        }
        session = null
        displayId = -1
        userId = 0
        lastError = null
    }
}
