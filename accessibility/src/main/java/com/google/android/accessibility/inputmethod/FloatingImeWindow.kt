package com.google.android.accessibility.inputmethod

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

class FloatingImeWindow(private val context: Context = appContext) {

    companion object {
        var floatingWindow: FloatingImeWindow? = null
        private var windowManager: WindowManager? = null
        private var floatingView: View? = null

        private var layoutParams: WindowManager.LayoutParams? = null
        private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences("floating_ime", Context.MODE_PRIVATE) }
    }

    fun show(accessibilityService: AccessibilityService? = com.google.android.accessibility.selecttospeak.accessibilityService) {
        if (floatingView != null) return // 已显示

        val wmContext: Context = accessibilityService?.applicationContext ?: context
        windowManager = wmContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 创建悬浮窗 View
        floatingView = Button(wmContext).apply {
            text = "切换回输入法"
            setBackgroundColor(0x88FF9800.toInt()) //半透明橙色
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                AliveUtils.toast(msg = "请选择输入法")
                AliveUtils.openAliveActivity()
            }

        }

        // 确定 Window 类型
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

        val startX = prefs.getInt("x", 0)
        val startY = prefs.getInt("y", 0)

        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            windowType,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = startX
            y = startY
        }

        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var lastX = 0f
            private var lastY = 0f
            private var initialX = 0
            private var initialY = 0
            private var isDragging = false
            private val touchSlop = 10 // 最小拖动距离，px

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                        layoutParams?.let {
                            initialX = it.x
                            initialY = it.y
                        }
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - lastX).toInt()
                        val dy = (event.rawY - lastY).toInt()

                        // 只有超过 touchSlop 才算拖动
                        if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            isDragging = true
                        }

                        if (isDragging) {
                            layoutParams?.let {
                                val newX = initialX + dx
                                val newY = initialY + dy

                                // 限制边界
                                it.x = newX.coerceIn(0, screenWidth - v.width)
                                it.y = newY.coerceIn(0, screenHeight - v.height)

                                // 更新布局，不闪烁
                                windowManager?.updateViewLayout(v, it)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 拖动结束保存位置
                        if (isDragging) {
                            layoutParams?.let {
                                prefs.edit().putInt("x", it.x).putInt("y", it.y).apply()
                            }
                            return true
                        }
                        // 如果没拖动就当点击处理
                        v.performClick()
                        return true
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