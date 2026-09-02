package com.google.android.accessibility.ext.utils.safecheck

import android.view.MotionEvent

/**
 * 脚本点击拦截委托：不依赖继承链、完全不影响原有的 setOnClickListener。
 *
 * 接入（放在你的 Activity 里；若已有统一 BaseActivity，只在 BaseActivity
 * 加一次即可全局生效）：
 * ```
 * private val touchGuard = TouchGuardDelegate()
 *
 * override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
 *     return if (touchGuard.shouldIntercept(ev)) true
 *            else super.dispatchTouchEvent(ev)
 * }
 * ```
 *
 * 原理：dispatchTouchEvent 是触摸事件进入 Activity 的最早入口，
 * 与微信一致只在 DOWN / UP / POINTER_DOWN / POINTER_UP 各独立取栈判断，
 * 命中即 return true 截断，事件不再向下分发到 View，因此各 View 的
 * onTouch / onClick 都不会触发。MOVE 等动作不取栈、直接放行。
 */
class TouchGuardDelegate {

    /**
     * @return true  表示本次事件应被截断（调用方直接 return true）；
     *         false 表示放行，继续走父类 [android.app.Activity.dispatchTouchEvent]。
     */
    fun shouldIntercept(ev: MotionEvent): Boolean {
        when (ev.action) {
            // 与微信一致：z.F(new int[]{0,1,5,6}, event.getAction())，
            // getAction() 未 mask，多指时 5/6 会带 pointerIndex 实际不命中。
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                // 抓栈 + 传栈判定。对齐微信 onInterceptTouchEvent：动作过滤通过后
                // new Throwable().getStackTrace()，再交给 provider.isEvilTraces(stack)。
                if (ScriptTouchDetector.isEvilTraces(Throwable().stackTrace)) return true
            }
        }
        return false
    }
}