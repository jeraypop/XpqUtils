package com.google.android.accessibility.ext.utils.verificationcode

import java.util.regex.Pattern

/**
 提取验证码
 * Description:This is OtpParser
 */
object OtpParser {
    // 验证码关键词检测
    val verificationKeywords = listOf(
        "验证码", "授权码", "随机码", "动态密码", "校验码", "内有效", "完成验证"
    )
    val patterns = arrayOf(
        "(?<=码(|是|为|：|:|是：|是:|为：|为:))(\\d{4,6})",
        "((?<=\\D)(\\d{4,6})(?=\\D))"
    )

    @JvmStatic
    fun parse(content: String): Pair<Boolean, String?> {

        if (!verificationKeywords.any { content.contains(it) }) {
            return Pair(false, null)
        }
        val cleanContent = content.replace(" ", "")
        for (pattern in patterns) {
            val matcher = Pattern.compile(pattern).matcher(cleanContent)
            if (matcher.find()) {
                val verificationCode = matcher.group(0)
                return Pair(true, verificationCode)
            }
        }
        return Pair(false, null)
    }
}