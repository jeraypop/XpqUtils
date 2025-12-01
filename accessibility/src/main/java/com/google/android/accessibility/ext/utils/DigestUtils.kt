package com.google.android.accessibility.ext.utils

import java.security.MessageDigest

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/12/2  0:01
 * Description:This is DigestUtils
 */
object DigestUtils {
    @JvmStatic
    fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.toHexString()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}