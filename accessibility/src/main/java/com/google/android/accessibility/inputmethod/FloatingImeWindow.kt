package com.google.android.accessibility.inputmethod

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import kotlin.math.abs

class FloatingImeWindow(private val context: Context = appContext) {

    companion object {
        var floatingWindow: FloatingImeWindow? = null
        private var windowManager: WindowManager? = null
        private var floatingView: View? = null
        private var layoutParams: WindowManager.LayoutParams? = null
        private val prefs: SharedPreferences by lazy {
            appContext.getSharedPreferences("floating_ime", Context.MODE_PRIVATE)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    fun show(accessibilityService: AccessibilityService? = null) {
        if (floatingView != null) return

        val wmContext: Context = accessibilityService ?: appContext
        windowManager = wmContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        floatingView = Button(wmContext).apply {
            text = "切换回输入法"
            setBackgroundColor(0x88FF9800.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                AliveUtils.toast(msg = "请选择输入法")
                AliveUtils.openAliveActivity()
            }
        }

        val windowType = when {
            accessibilityService != null -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else
                    LayoutParams.TYPE_PHONE
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (Settings.canDrawOverlays(context))
                    LayoutParams.TYPE_APPLICATION_OVERLAY
                else {
                    requestOverlayPermission()
                    return
                }
            }
            else -> LayoutParams.TYPE_PHONE
        }

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val savedX = prefs.getInt("x", -1)
        val savedY = prefs.getInt("y", -1)

        // 第一次显示，默认右侧中间
        val startX: Int
        val startY: Int
        if (savedX == -1 || savedY == -1) {
            floatingView?.measure(
                View.MeasureSpec.UNSPECIFIED,
                View.MeasureSpec.UNSPECIFIED
            )
            val viewWidth = floatingView?.measuredWidth ?: 100
            val viewHeight = floatingView?.measuredHeight ?: 100

            startX = screenWidth - viewWidth
            startY = (screenHeight - viewHeight) / 2
        } else {
            startX = savedX
            startY = savedY
        }

        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            windowType,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var lastRawX = 0f
            private var lastRawY = 0f
            private var isDragging = false
            private val touchSlop = 10

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                layoutParams?.let { params ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            lastRawX = event.rawX
                            lastRawY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - lastRawX
                            val dy = event.rawY - lastRawY

                            if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                isDragging = true
                            }

                            if (isDragging) {
                                params.x = (params.x + dx).toInt()
                                    .coerceIn(0, screenWidth - v.width)
                                params.y = (params.y + dy).toInt()
                                    .coerceIn(0, screenHeight - v.height)
                                windowManager?.updateViewLayout(v, params)
                                lastRawX = event.rawX
                                lastRawY = event.rawY
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isDragging) {
                                // 吸边动画
                                val targetX = if (params.x + v.width / 2 < screenWidth / 2) {
                                    0
                                } else {
                                    screenWidth - v.width
                                }
                                animateToX(params, v, targetX)
                                return true
                            }
                            v.performClick()
                            return true
                        }
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(floatingView, layoutParams)
            Log.i("FloatingImeWindow", "悬浮窗显示，类型=$windowType")
        } catch (e: Exception) {
            Log.e("FloatingImeWindow", "显示悬浮窗失败: ${e.message}")
            AliveUtils.toast(msg = "悬浮窗权限不足，请允许悬浮窗权限")
        }
    }

    private fun animateToX(params: LayoutParams, v: View, targetX: Int) {
        val duration = 200L
        val frameRate = 16L
        val startX = params.x
        val distance = targetX - startX
        val steps = (duration / frameRate).toInt()
        var currentStep = 0

        handler.post(object : Runnable {
            override fun run() {
                if (currentStep < steps) {
                    val fraction = (currentStep + 1).toFloat() / steps
                    params.x = startX + (distance * fraction).toInt()
                    windowManager?.updateViewLayout(v, params)
                    currentStep++
                    handler.postDelayed(this, frameRate)
                } else {
                    params.x = targetX
                    windowManager?.updateViewLayout(v, params)
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                }
            }
        })
    }

    fun hide() {
        floatingView?.let {
            windowManager?.removeView(it)
            floatingView = null
            layoutParams = null
            Log.i("FloatingImeWindow", "悬浮窗已移除")
        }
    }

    private fun requestOverlayPermission() {
        AliveUtils.toast(msg = "请开启悬浮窗权限")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}