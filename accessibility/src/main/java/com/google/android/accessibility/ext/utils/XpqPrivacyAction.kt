package com.google.android.accessibility.ext.utils

/**
 * 隐私弹窗的按钮回调。
 *
 * 之所以不直接用 [Runnable]：Kotlin 调 [java.lang.Runnable] 参数必须写 `Runnable { }`，
 * 而 `fun interface` 在两边都是裸 lambda —— Kotlin 写 `{ ... }`，Java 写 `() -> { ... }`。
 *
 * 回调都在主线程、在弹窗 `dismiss()` 之后触发；触发时同意状态已经写进 MMKV
 * （[AppInfoUtil.getPrivacyPolicy] 已是新值），宿主可以放心在里面直接读。
 */
fun interface XpqPrivacyAction {
    fun onAction()
}
