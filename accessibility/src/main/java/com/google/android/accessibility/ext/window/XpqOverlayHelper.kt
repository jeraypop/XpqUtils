package com.google.android.accessibility.ext.window

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/10/2  23:38
 * Description:This is XpqOverlayHelper
 */
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager



object XpqOverlayHelper {
    private const val TAG = "OverlayHelper"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addOverlaySafely(ctx: Context, view: View, x: Int = 0, y: Int = 0): Boolean {
        // ensure main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { addOverlaySafely(ctx, view, x, y) }
            return true
        }

        // avoid double-add
        if (view.parent != null) {
            Log.w(TAG, "view already has parent")
            return true
        }

        // AccessibilityService -> prefer TYPE_ACCESSIBILITY_OVERLAY
        if (ctx is AccessibilityService) {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
//            val wm = ctx.getSystemService(WindowManager::class.java)


            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // TYPE_ACCESSIBILITY_OVERLAY exists on modern API
                    try {
                        val f = WindowManager.LayoutParams::class.java.getField("TYPE_ACCESSIBILITY_OVERLAY")
                        f.getInt(null)
                    } catch (t: Throwable) {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = x
            lp.y = y
            return try {
                wm.addView(view, lp); true
            } catch (t: Throwable) {
                Log.e(TAG, "addView accessibility failed", t)
                false
            }
        }

        // Otherwise try system overlay (ApplicationContext)
        val appCtx = ctx.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val useAppOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(appCtx)

        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && useAppOverlay -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // no overlay permission -> fallback to activity root if possible
                if (ctx is Activity) {
                    return addToActivityRoot(ctx, view)
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            }
            else -> {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y

        return try {
            wm.addView(view, lp); true
        } catch (t: Exception) {
            Log.e(TAG, "addView failed, fallback to activity root if possible", t)
            if (ctx is Activity) addToActivityRoot(ctx, view) else false
        }
    }

    fun removeOverlaySafely(ctx: Context, view: View) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { removeOverlaySafely(ctx, view) }
            return
        }

        if (ctx is Activity) {
            try {
                val decor = ctx.window?.decorView as? ViewGroup
                decor?.removeView(view)
            } catch (t: Throwable) { /* ignore */ }
        }

        try {
            val wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(view)
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun addToActivityRoot(activity: Activity, view: View): Boolean {
        return try {
            val decor = activity.window?.decorView as? ViewGroup
            decor?.addView(view)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "addToActivityRoot failed", t)
            false
        }
    }
}
