package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import com.google.android.accessibility.ext.utils.KeyguardUnLock.showClickIndicator
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/1/9  12:16
 * Description:This is StableGestureClicker
 */
object StableGestureClicker {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    private val mutex = Mutex()

    /**
     * 串行、稳定的真实点击
     */
    @JvmStatic
    @JvmOverloads
    fun click(
        service: AccessibilityService? = accessibilityService,
        x: Int,
        y: Int,
        duration: Long = 60L
    ): Boolean {
        service ?: return false
        if (x <= 0 || y <= 0) return false
        //if (service.rootInActiveWindow == null) return false

        scope.launch {
            mutex.withLock {
                try {
                    dispatch(service, x, y, duration)
                    delay(120) // 给系统一点喘息时间（非常重要）
                    showClickIndicator(
                        service,
                        x,
                        y
                    )
                } catch (_: Throwable) {
                }
            }
        }

        return true
    }

    private fun dispatch(
        service: AccessibilityService,
        x: Int,
        y: Int,
        duration: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    duration
                )
            )
            .build()

        try {
            service.dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
        }
    }


    /**
     * suspend 版真实点击
     *
     * @return true  = 手势完成（onCompleted）
     *         false = 手势取消 / 条件不满足
     */
    suspend fun clickAwait(
        service: AccessibilityService,
        x: Int,
        y: Int,
        duration: Long = 60L,
        timeoutMs: Long = if (isSystemUi(service)) 1500L else 400L
    ): Boolean {

        if (x <= 0 || y <= 0) return false
        //if (service.rootInActiveWindow == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        return mutex.withLock {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->

                    val path = Path().apply {
                        moveTo(x.toFloat(), y.toFloat())
                    }

                    val gesture = GestureDescription.Builder()
                        .addStroke(
                            GestureDescription.StrokeDescription(
                                path, 0, duration
                            )
                        )
                        .build()

                    try {
                        val dispatched = service.dispatchGesture(
                            gesture,
                            object : AccessibilityService.GestureResultCallback() {

                                override fun onCompleted(
                                    gestureDescription: GestureDescription?
                                ) {
                                    if (cont.isActive) {
                                        cont.resume(true)
                                    }
                                }

                                override fun onCancelled(
                                    gestureDescription: GestureDescription?
                                ) {
                                    if (cont.isActive) {
                                        cont.resume(false)
                                    }
                                }
                            },
                            null
                        )

                        if (!dispatched && cont.isActive) {
                            cont.resume(false)
                        }

                    } catch (_: Throwable) {
                        if (cont.isActive) {
                            cont.resume(false)
                        }
                    }

                    cont.invokeOnCancellation {
                        // 手势一旦 dispatch，系统不支持强制取消
                        // 这里只做资源收尾即可
                    }
                }
            } ?: false
        }
    }

    private fun isSystemUi(service: AccessibilityService): Boolean {
        val pkg = service.rootInActiveWindow?.packageName?.toString()
        return pkg == "com.android.systemui"
    }


}
