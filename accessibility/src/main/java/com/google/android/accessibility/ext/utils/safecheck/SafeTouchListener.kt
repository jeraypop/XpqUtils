package com.google.android.accessibility.ext.utils.safecheck

import android.view.MotionEvent
import android.view.View

/**
 * 包装 [View.OnTouchListener]：与微信 [MMAccTouchListenerWrap] 一致，
 * 只在关键触摸动作（DOWN / UP / POINTER_DOWN / POINTER_UP）处取栈判断，
 * 每个动作都独立全新取栈、互不复用；MOVE 等高频动作不取栈、直接放行。
 *
 * 微信侧对应 AccProviderFactory.onInterceptTouchEvent：
 * `z.F(new int[]{0,1,5,6}, event.getAction())` 通过后才 `new Throwable().getStackTrace()`。
 */
class SafeTouchListener(
    private val real: (View, MotionEvent) -> Boolean,
) : View.OnTouchListener {

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return when (event.action) {
            // 与微信一致：z.F(new int[]{0,1,5,6}, event.getAction())，
            // getAction() 未 mask，多指时 5/6 会带 pointerIndex 实际不命中。
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                // 抓取当前触摸入口的完整调用链再判定。对齐微信 onInterceptTouchEvent：
                // 动作过滤通过后 new Throwable().getStackTrace()，再 provider.isEvilTraces(stack)。
                val stack = Throwable().stackTrace
                if (ScriptTouchDetector.isEvilTraces(stack)) true else real(v, event)
            }

            else -> real(v, event)
        }
    }
}