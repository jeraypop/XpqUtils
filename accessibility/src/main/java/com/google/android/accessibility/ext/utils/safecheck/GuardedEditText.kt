package com.google.android.accessibility.ext.utils.safecheck

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/**
 * 本地最小版：EditText 的 setText 调用栈检测，对齐微信 MMEditText.setText()。
 *
 * 场景：脚本靠无障碍「自动填字」，走的是 ACTION_SET_TEXT / ACTION_PASTE，
 * 不产生 MotionEvent、也不走点击，最终只会回调到目标 View 的 setText()。
 * 因此在 setText 这个必经入口抓当前调用栈，命中 evil 特征即拦截（不改动已有内容）。
 *
 * 为什么只重写两参 BufferType 版本（与微信一致）：
 *  - TextView.setText(CharSequence) 是 final，内部转调 setText(text, bufferType)
 *  - setText(int) / setText(int, BufferType) 也是 final，最终同样汇到两参版本
 *  所以拦两参版本即可覆盖绝大多数写入路径。
 *
 * 依赖：ScriptTouchDetector（第一套调用栈判定，evilStackList 里已含
 * `android.view.accessibility.AccessibilityNodeInfo.performAction` 等无障碍 setText 特征）。
 */
open class GuardedEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : androidx.appcompat.widget.AppCompatEditText(context, attrs, defStyleAttr) {

    /** 命中脚本时触发，对应微信 IAccessibilityService.onInjectionIntercept 上报。 */
    var onInjectionIntercept: (() -> Unit)? = null

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        if (ScriptTouchDetector.isEvilTraceNow()) {
            // 疑似脚本写文本，直接跳过 super.setText，已有内容保持不变
            onInjectionIntercept?.invoke()
            return
        }
        super.setText(text, type)
    }
}