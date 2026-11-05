package com.google.android.accessibility.ext.vdisplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import com.google.android.accessibility.uiautomation.shizuku.AutomationShizuku
import com.google.android.accessibility.uiautomation.shizuku.IAutomationUserService
import com.google.android.accessibility.uiautomation.util.HiddenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * XpqUtils 原生虚拟屏入口。
 *
 * 用 Shizuku 的 shell UserService 建一块可信虚拟屏（独立显示组、独立焦点，不抢主屏），
 * 把第三方 App 启动到这块屏上执行；画面通过 [Session.frames] / [Session.latestFramePng] 读取。
 *
 * 用法：
 * ```
 * val session = XpqVDisplay.create(context) ?: return   // 需要 Shizuku 已运行且授权
 * session.launch("com.tencent.mm")
 * XpqAcc.useDisplay(session.displayId)                 // 让 Xpq 的节点读取走这块屏
 * ...
 * XpqAcc.clearDisplay()
 * session.release()
 * ```
 *
 * 读这块屏上的节点需要宿主无障碍服务处于开启状态：系统只把
 * `windowsOnAllDisplays` 暴露给真实的 AccessibilityService。
 */
/** 虚拟屏能力的统一入口：UserService（远程）或 App 内 binder 后端（本机）。 */
internal interface VDisplayApi {
    fun createDisplay(surface: android.view.Surface?, width: Int, height: Int, dpi: Int): Int
    fun releaseDisplay(displayId: Int)
    fun startAppOnDisplay(displayId: Int, packageName: String): Int
    fun startAppInUser(displayId: Int, packageName: String, userId: Int): Int
    fun injectTapOnDisplay(displayId: Int, x: Float, y: Float): Boolean
    fun injectKeyOnDisplay(displayId: Int, keyCode: Int): Boolean
    fun injectSwipeOnDisplay(
        displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
    ): Boolean
    fun injectGesture(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean
    fun topTaskIdOnDisplay(displayId: Int): Int
    fun displayOfTask(taskId: Int): Int
    fun userIdOfTask(taskId: Int): Int
    fun packageOfTask(taskId: Int): String
    fun endTasksOnDisplay(displayId: Int): Boolean
    fun dualUserIdForPackage(packageName: String): Int
    fun setClipText(text: String): Boolean
}

/** 通过 Shizuku UserService（shell 进程）执行。 */
private class RemoteVDisplayApi(private val service: IAutomationUserService) : VDisplayApi {
    override fun createDisplay(surface: android.view.Surface?, width: Int, height: Int, dpi: Int): Int =
        if (surface == null) -1 else service.createDisplay(surface, width, height, dpi)

    override fun releaseDisplay(displayId: Int) = service.releaseDisplay(displayId)
    override fun startAppOnDisplay(displayId: Int, packageName: String): Int =
        service.startAppOnDisplay(displayId, packageName)

    override fun startAppInUser(displayId: Int, packageName: String, userId: Int): Int =
        service.startAppInUser(displayId, packageName, userId)

    override fun injectTapOnDisplay(displayId: Int, x: Float, y: Float): Boolean =
        service.injectTapOnDisplay(displayId, x, y)

    override fun injectKeyOnDisplay(displayId: Int, keyCode: Int): Boolean =
        service.injectKeyOnDisplay(displayId, keyCode)

    override fun injectSwipeOnDisplay(
        displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
    ): Boolean = service.injectSwipeOnDisplay(displayId, x1, y1, x2, y2, durationMs)

    override fun injectGesture(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        service.injectGesture(displayId, xs, ys, times)

    override fun topTaskIdOnDisplay(displayId: Int): Int = service.topTaskIdOnDisplay(displayId)
    override fun displayOfTask(taskId: Int): Int = service.displayOfTask(taskId)
    override fun userIdOfTask(taskId: Int): Int = service.userIdOfTask(taskId)
    override fun packageOfTask(taskId: Int): String = service.packageOfTask(taskId)
    override fun endTasksOnDisplay(displayId: Int): Boolean = service.endTasksOnDisplay(displayId)
    override fun dualUserIdForPackage(packageName: String): Int =
        service.dualUserIdForPackage(packageName)

    override fun setClipText(text: String): Boolean = service.setClipText(text)
}

/** 在 App 进程内用 ShizukuBinderWrapper 执行（加固导致 UserService 不可用时的兜底）。 */
private class LocalVDisplayApi(private val service: VDisplayService) : VDisplayApi {
    override fun createDisplay(surface: android.view.Surface?, width: Int, height: Int, dpi: Int): Int {
        if (surface == null) return -1
        val id = service.createDisplay(surface, width, height, dpi)
        // 把真实失败原因带出去：加固包的 Log 会被清掉，只能靠任务日志
        if (id < 0) throw IllegalStateException("createDisplay failed: ${service.lastFailure ?: "unknown"}")
        return id
    }

    override fun releaseDisplay(displayId: Int) = service.releaseDisplay(displayId)
    override fun startAppOnDisplay(displayId: Int, packageName: String): Int =
        service.startApp(displayId, packageName)

    override fun startAppInUser(displayId: Int, packageName: String, userId: Int): Int =
        service.startApp(displayId, packageName, userId)

    override fun injectTapOnDisplay(displayId: Int, x: Float, y: Float): Boolean =
        service.injectTap(displayId, x, y)

    override fun injectKeyOnDisplay(displayId: Int, keyCode: Int): Boolean =
        service.injectKey(displayId, keyCode)

    override fun injectSwipeOnDisplay(
        displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
    ): Boolean = service.injectSwipe(displayId, x1, y1, x2, y2, durationMs)

    override fun injectGesture(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        service.injectGesture(displayId, xs, ys, times)

    override fun topTaskIdOnDisplay(displayId: Int): Int = service.topTaskId(displayId)
    override fun displayOfTask(taskId: Int): Int = service.displayOfTask(taskId)
    override fun userIdOfTask(taskId: Int): Int = service.userIdOfTask(taskId)
    override fun packageOfTask(taskId: Int): String = service.packageOfTask(taskId)
    override fun endTasksOnDisplay(displayId: Int): Boolean = service.endTasksOnDisplay(displayId)
    override fun dualUserIdForPackage(packageName: String): Int =
        service.dualUserIdForPackage(packageName)

    override fun setClipText(text: String): Boolean = service.setClipText(text)
}

object XpqVDisplay {
    private const val TAG = "XpqVDisplay"

    @Volatile
    private var service: IAutomationUserService? = null

    /** 加固等导致 UserService 绑定失败后，App 进程内的 binder 后端。 */
    @Volatile
    private var localService: VDisplayService? = null

    /** 一旦 UserService 绑定失败，本进程后续都直接用 binder 后端，避免每次任务白等超时。 */
    @Volatile
    private var preferBinder = false

    /** 调试开关：`adb shell setprop debug.xpq.vdisplay.binder 1` 可强制 binder 后端（进程内只读一次）。 */
    @Volatile
    private var forcedBinder: Boolean? = null

    private fun forceBinderBackend(): Boolean = forcedBinder ?: runCatching {
        HiddenApi.ensure()
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, "debug.xpq.vdisplay.binder", false) as Boolean
    }.getOrDefault(false).also { forcedBinder = it }

    /** [Session.launch] / [Session.launchInUser] 的返回值。 */
    const val LAUNCH_OK = 0
    const val LAUNCH_IN_USE_ON_MAIN = 1
    const val LAUNCH_NOT_FOUND = 2
    const val LAUNCH_ERROR = 3
    /** 系统没在目标用户里建任务（通常是把机主那份拉起来了）。 */
    const val LAUNCH_USER_MISMATCH = 4

    /** Shizuku 在运行且已授权。 */
    fun isReady(): Boolean =
        runCatching { AutomationShizuku.isInstalled() && AutomationShizuku.isPermissionGranted() }
            .getOrDefault(false)

    /** 虚拟屏总开关（库内设置）。 */
    fun isEnabled(context: Context): Boolean = VDisplaySettings.isEnabled(context)

    /** 虚拟屏模式下锁屏时是否先解锁主屏（开启走剪贴板，关闭走节点输入）。 */
    fun shouldUnlock(context: Context): Boolean = VDisplaySettings.shouldUnlock(context)

    /** 最近一次建屏失败的具体原因（含后端名），供宿主写入任务日志。 */
    @Volatile
    var lastCreateError: String? = null
        private set

    /** 当前走的是哪个后端；供日志/调试确认。 */
    val backendName: String get() = when {
        service != null -> "UserService"
        localService != null -> "BinderWrapper"
        else -> "none"
    }

    /** 是否显示虚拟屏预览窗（库内设置）。 */
    fun isPreviewEnabled(context: Context): Boolean = VDisplaySettings.isPreviewEnabled(context)

    /**
     * 弹出库内的虚拟屏设置对话框。
     * [showDualSwitch] 控制是否显示第 4 个「启动分身版」开关；
     * 不显示时由宿主自己决定启动主应用还是分身版。
     */
    @JvmOverloads
    @JvmStatic
    fun showSettingsDialog(context: Context, showDualSwitch: Boolean = false) =
        VDisplaySettings.showDialog(context, showDualSwitch)

    /** 设置里是否勾选了「启动分身版」；默认 false（主应用版本）。 */
    fun shouldUseDual(context: Context): Boolean = VDisplaySettings.isDualEnabled(context)

    /** 显示副屏实时画面预览窗（需要悬浮窗权限）；位置、大小可在悬浮窗里拖动调整。 */
    @JvmOverloads
    fun showPreview(context: Context, session: Session? = null) = XpqVDisplayPreview.show(context, session)

    /** 隐藏预览窗。 */
    fun hidePreview() = XpqVDisplayPreview.hide()

    /** 预览窗当前是否显示。 */
    fun isPreviewShown(): Boolean = XpqVDisplayPreview.isShown()

    /** 请求 Shizuku 授权；回调在主线程。 */
    fun requestPermission(onResult: (Boolean) -> Unit) = AutomationShizuku.requestPermission(onResult)

    /** 已绑定的 UserService；仅供同模块/调试查询。 */
    fun boundService(): IAutomationUserService? = service

    /** 绑定 UserService 的最长等待：加固包拉起即崩，等太久会拖慢第一次建屏。 */
    private const val BIND_TIMEOUT_MS = 3_000L

    /**
     * 连接 UserService 并建一块虚拟屏。Shizuku 没运行/没授权/建屏失败时返回 null。
     * 必须在主线程之外调用（内部阻塞等待绑定）。
     */
    suspend fun create(
        context: Context,
        width: Int = -1,
        height: Int = -1,
        dpi: Int = -1,
        timeoutMs: Long = BIND_TIMEOUT_MS,
    ): Session? = withContext(Dispatchers.IO) {
        val forced = forceBinderBackend()
        val svc = if (preferBinder || forced) {
            null
        } else {
            AutomationShizuku.bind(context.applicationContext, timeoutMs)
        }
        if (svc != null) {
            service = svc
        } else {
            if (!preferBinder) {
                preferBinder = true
                Log.w(
                    TAG,
                    if (forced) "binder backend forced by debug property"
                    else "Shizuku UserService not connected, using shell binder backend"
                )
            }
        }
        val api = apiFor(context) ?: return@withContext null
        val firstBackend = if (service != null) "UserService" else "BinderWrapper"
        val metrics = screenMetrics(context)
        val w = width.takeIf { it > 0 } ?: metrics.widthPixels
        val h = height.takeIf { it > 0 } ?: metrics.heightPixels
        val d = dpi.takeIf { it > 0 } ?: metrics.densityDpi
        var session = createSession(api, w, h, d, firstBackend)
        if (session == null && service != null) {
            // UserService 绑上了但实际建屏失败（进程半死/权限异常）：再试一次 binder 后端
            Log.w(TAG, "UserService backend failed, retrying with shell binder backend")
            service = null
            preferBinder = true
            val fallback = apiFor(context)
            if (fallback != null) session = createSession(fallback, w, h, d, "BinderWrapper")
        }
        session
    }

    private fun createSession(
        api: VDisplayApi,
        width: Int,
        height: Int,
        dpi: Int,
        backend: String,
    ): Session? = runCatching { Session(api, width, height, dpi) }
        .onSuccess { lastCreateError = null }
        .onFailure {
            lastCreateError = "$backend 后端建屏失败：${it.javaClass.simpleName}: ${it.message}"
            Log.e(TAG, "create virtual display failed ($backend)", it)
        }
        .getOrNull()

    /** 取当前可用的执行后端；UserService 优先，没有则初始化 App 内 binder 后端。 */
    private fun apiFor(context: Context): VDisplayApi? {
        service?.let { return RemoteVDisplayApi(it) }
        val local = localService ?: runCatching {
            VDisplayService(ShizukuVDisplayAccess(context.applicationContext)).also { localService = it }
        }.onFailure { Log.w(TAG, "binder backend init failed", it) }.getOrNull() ?: return null
        return LocalVDisplayApi(local)
    }

    private fun apiOrNull(): VDisplayApi? =
        service?.let { RemoteVDisplayApi(it) } ?: localService?.let { LocalVDisplayApi(it) }

    internal fun injectBack(displayId: Int): Boolean =
        apiOrNull()?.injectKeyOnDisplay(displayId, KeyEvent.KEYCODE_BACK) ?: false

    internal fun injectKey(displayId: Int, keyCode: Int): Boolean =
        apiOrNull()?.injectKeyOnDisplay(displayId, keyCode) ?: false

    internal fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        apiOrNull()?.injectTapOnDisplay(displayId, x.toFloat(), y.toFloat()) ?: false

    /**
     * 自动识别的分身空间用户 ID；没有分身返回 0（机主）。
     * 传 [packageName] 时只认「装了该包」的用户，避免把工作资料/访客用户误当成分身空间。
     */
    @JvmOverloads
    fun dualUserId(packageName: String? = null): Int = service?.let {
        if (packageName.isNullOrEmpty()) it.dualUserId() else it.dualUserIdForPackage(packageName)
    } ?: localService?.let {
        if (packageName.isNullOrEmpty()) it.dualUserId() else it.dualUserIdForPackage(packageName)
    } ?: 0

    /**
     * 自动识别分身空间用户 ID（挂起版）：先确保 Shizuku UserService 已绑定；
     * 加固导致 UserService 不可用时改用 App 内 binder 后端，不再把主版本当成分身版。
     *
     * 宿主在任务开始前判断「启动主应用还是分身版」时应使用这个版本。
     */
    suspend fun dualUserId(context: Context, packageName: String? = null): Int =
        withContext(Dispatchers.IO) {
            val svc = if (preferBinder || forceBinderBackend()) null else service ?: run {
                AutomationShizuku.bind(context.applicationContext, BIND_TIMEOUT_MS)?.also { service = it }
            }
            if (svc != null) {
                val id = runCatching {
                    if (packageName.isNullOrEmpty()) svc.dualUserId()
                    else svc.dualUserIdForPackage(packageName)
                }
                if (id.isSuccess) return@withContext id.getOrThrow()
                // UserService 半死：丢掉它，本次改走 binder 后端
                Log.w(TAG, "dual-user query via UserService failed, falling back", id.exceptionOrNull())
                service = null
                preferBinder = true
            }
            if (!preferBinder) {
                preferBinder = true
                Log.w(TAG, "UserService unavailable, dual-user detection via shell binder backend")
            }
            val local = localService ?: runCatching {
                VDisplayService(ShizukuVDisplayAccess(context.applicationContext)).also { localService = it }
            }.onFailure { Log.w(TAG, "binder backend init failed", it) }.getOrNull()
                ?: return@withContext 0
            if (packageName.isNullOrEmpty()) local.dualUserId() else local.dualUserIdForPackage(packageName)
        }

    /** 以 shell 身份写系统剪贴板；binder 后端不支持时返回 false（调用方改走节点输入）。 */
    internal fun setClipText(text: String): Boolean = apiOrNull()?.setClipText(text) ?: false

    /** 按时间逐点注入虚拟屏上的一笔（GestureDescription 转成的轨迹）。 */
    internal fun injectGesture(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        apiOrNull()?.injectGesture(displayId, xs, ys, times) ?: false

    private fun screenMetrics(context: Context): DisplayMetrics {
        val dm = context.getSystemService(DisplayManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0) {
            @Suppress("DEPRECATION")
            metrics.setTo(context.resources.displayMetrics)
        }
        return metrics
    }

    /** 一块已经建好的虚拟屏及其画面流。 */
    class Session internal constructor(
        private val api: VDisplayApi,
        val width: Int,
        val height: Int,
        dpi: Int,
    ) {
        private val thread = HandlerThread("xpq-vdisplay-frames").apply { start() }
        private val handler = Handler(thread.looper)
        private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val displayId: Int

        private val _frames = MutableStateFlow<Bitmap?>(null)
        /** 实时画面：限速 ≤10 帧/秒，缩到宽 ≤360。 */
        val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

        private val frameLock = Any()
        private var fullFrame: Bitmap? = null
        private var hasFrame = false
        private var lastProcessedAt = 0L
        private var pending = false
        @Volatile private var released = false

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val previewWidth = minOf(PREVIEW_MAX_WIDTH, width)
        private val previewHeight = maxOf(1, height * previewWidth / width)

        private val process = Runnable {
            pending = false
            lastProcessedAt = SystemClock.uptimeMillis()
            processLatest()
        }

        init {
            reader.setOnImageAvailableListener({ schedule() }, handler)
            val id = try {
                api.createDisplay(reader.surface, width, height, dpi)
            } catch (t: Throwable) {
                closeLocal()
                throw t
            }
            if (id < 0) {
                closeLocal()
                throw IllegalStateException("createDisplay failed")
            }
            displayId = id
            Log.i(TAG, "session created display=$id ${width}x$height@$dpi")
        }

        private fun schedule() {
            if (pending || released) return
            pending = true
            val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
        }

        private fun processLatest() {
            if (released) return
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
            try {
                val plane = image.planes[0]
                val strideWidth = plane.rowStride / plane.pixelStride
                synchronized(frameLock) {
                    var bmp = fullFrame
                    if (bmp == null || bmp.width != strideWidth) {
                        bmp?.recycle()
                        bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                        fullFrame = bmp
                    }
                    bmp.copyPixelsFromBuffer(plane.buffer.rewind())
                    hasFrame = true
                    val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                    Canvas(preview).drawBitmap(bmp, Rect(0, 0, width, height),
                        Rect(0, 0, previewWidth, previewHeight), paint)
                    _frames.value = preview
                }
            } finally {
                image.close()
            }
        }

        /** 最新一帧的原始尺寸副本；调用方负责 recycle。 */
        fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
            val bmp = fullFrame
            if (!hasFrame || bmp == null) null else Bitmap.createBitmap(bmp, 0, 0, width, height)
        }

        fun latestFramePng(): ByteArray? {
            val crop = latestFrameBitmap() ?: return null
            return try {
                ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            } finally {
                crop.recycle()
            }
        }

        /** 把 [packageName] 启动到这块屏。0=成功，1=主屏正在用，2=无入口，3=出错。 */
        fun launch(packageName: String): Int = api.startAppOnDisplay(displayId, packageName)

        /** 在 [userId] 那份用户（分身空间）里启动 [packageName] 到这块屏。 */
        fun launchInUser(packageName: String, userId: Int): Int =
            api.startAppInUser(displayId, packageName, userId)

        fun tap(x: Int, y: Int): Boolean = api.injectTapOnDisplay(displayId, x.toFloat(), y.toFloat())

        fun key(keyCode: Int): Boolean = api.injectKeyOnDisplay(displayId, keyCode)

        fun back(): Boolean = key(KeyEvent.KEYCODE_BACK)

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean =
            api.injectSwipeOnDisplay(displayId, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)

        fun topTaskId(): Int = api.topTaskIdOnDisplay(displayId)

        fun displayOfTask(taskId: Int): Int = api.displayOfTask(taskId)

        /** 任务所属用户；查不到为 -1。 */
        fun userIdOfTask(taskId: Int): Int = api.userIdOfTask(taskId)

        /** 任务所属包名；查不到为空串。 */
        fun packageOfTask(taskId: Int): String = api.packageOfTask(taskId)

        /** 结束这块屏上的所有任务（先于 [release]，避免任务被系统挪回主屏）。 */
        fun endTasks(): Boolean = api.endTasksOnDisplay(displayId)

        fun release() {
            if (released) return
            released = true
            runCatching { api.releaseDisplay(displayId) }
            closeLocal()
            _frames.value = null
        }

        private fun closeLocal() {
            released = true
            handler.removeCallbacksAndMessages(null)
            handler.post {
                reader.close()
                synchronized(frameLock) {
                    fullFrame?.recycle()
                    fullFrame = null
                    hasFrame = false
                }
            }
            thread.quitSafely()
        }

        private companion object {
            const val PREVIEW_MAX_WIDTH = 360
            const val FRAME_INTERVAL_MS = 100L
        }
    }
}
