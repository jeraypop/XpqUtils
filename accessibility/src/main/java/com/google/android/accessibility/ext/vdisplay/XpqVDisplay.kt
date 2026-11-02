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
object XpqVDisplay {
    private const val TAG = "XpqVDisplay"

    @Volatile
    private var service: IAutomationUserService? = null

    /** Shizuku 在运行且已授权。 */
    fun isReady(): Boolean =
        runCatching { AutomationShizuku.isInstalled() && AutomationShizuku.isPermissionGranted() }
            .getOrDefault(false)

    /** 请求 Shizuku 授权；回调在主线程。 */
    fun requestPermission(onResult: (Boolean) -> Unit) = AutomationShizuku.requestPermission(onResult)

    /** 已绑定的 UserService；仅供同模块/调试查询。 */
    fun boundService(): IAutomationUserService? = service

    /**
     * 连接 UserService 并建一块虚拟屏。Shizuku 没运行/没授权/建屏失败时返回 null。
     * 必须在主线程之外调用（内部阻塞等待绑定）。
     */
    suspend fun create(
        context: Context,
        width: Int = -1,
        height: Int = -1,
        dpi: Int = -1,
        timeoutMs: Long = 10_000,
    ): Session? = withContext(Dispatchers.IO) {
        val svc = AutomationShizuku.bind(context.applicationContext, timeoutMs) ?: run {
            Log.w(TAG, "Shizuku UserService not connected")
            return@withContext null
        }
        service = svc
        val metrics = screenMetrics(context)
        val w = width.takeIf { it > 0 } ?: metrics.widthPixels
        val h = height.takeIf { it > 0 } ?: metrics.heightPixels
        val d = dpi.takeIf { it > 0 } ?: metrics.densityDpi
        runCatching { Session(svc, w, h, d) }
            .onFailure { Log.e(TAG, "create virtual display failed", it) }
            .getOrNull()
    }

    internal fun injectBack(displayId: Int): Boolean =
        service?.injectKeyOnDisplay(displayId, KeyEvent.KEYCODE_BACK) ?: false

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
        private val service: IAutomationUserService,
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
                service.createDisplay(reader.surface, width, height, dpi)
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
        fun launch(packageName: String): Int = service.startAppOnDisplay(displayId, packageName)

        fun tap(x: Int, y: Int): Boolean = service.injectTapOnDisplay(displayId, x.toFloat(), y.toFloat())

        fun key(keyCode: Int): Boolean = service.injectKeyOnDisplay(displayId, keyCode)

        fun back(): Boolean = key(KeyEvent.KEYCODE_BACK)

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean =
            service.injectSwipeOnDisplay(displayId, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)

        fun topTaskId(): Int = service.topTaskIdOnDisplay(displayId)

        fun displayOfTask(taskId: Int): Int = service.displayOfTask(taskId)

        fun release() {
            if (released) return
            released = true
            runCatching { service.releaseDisplay(displayId) }
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
