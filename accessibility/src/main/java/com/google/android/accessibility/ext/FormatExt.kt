package com.google.android.accessibility.ext

//fun String?.default(default: String = "") = if (this.isNullOrBlank()) default else this
//
//fun CharSequence?.default(default: String = "") =
//    if (this.isNullOrBlank()) default else this.toString()

fun CharSequence?.default(
    default: String = "",
    filter: Boolean = false
): String {
    val origin = if (this.isNullOrBlank()) default else this.toString()
    return if (filter) {
        // 匹配：中文汉字 + ASCII可见字符（从!到~，即\u0021-\u007E）
        origin.replace(Regex("[^\u4e00-\u9fff\u0021-\u007E]"), "")
    } else origin
}

// 同步修改String扩展函数
fun String?.default(
    default: String = "",
    filter: Boolean = false
) = (this ?: default).let {
    if (filter) it.replace(Regex("[^\u4e00-\u9fff\u0021-\u007E]"), "") else it
}
