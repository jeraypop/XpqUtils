package com.google.android.accessibility.ext.utils

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/12/15  14:05
 * Description:This is XPQFileUtils
 */
object XPQFileUtils {
    /**
     * 清空指定目录下的所有文件和子目录
     * @param directory 要清空的目录
     */
    @JvmStatic
    fun cleanDirectory(directory: File) {
        if (directory.exists() && directory.isDirectory()) {
            val files = directory.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isDirectory()) {
                        // 递归删除子目录
                        cleanDirectory(file)
                    }
                    // 删除文件或空目录
                    file.delete()
                }
            }
        }
    }
    @JvmStatic
    @Throws(IOException::class)
    fun readFileToString(file: File, charset: Charset): String {
        val content = StringBuilder()
        BufferedReader(InputStreamReader(FileInputStream(file), charset)).use { reader ->
            val buffer = CharArray(8192) // 8KB缓冲区
            var length: Int
            while (reader.read(buffer).also { length = it } != -1) {
                content.append(buffer, 0, length)
            }
        }
        return content.toString()
    }
    @JvmStatic
    @Throws(IOException::class)
    fun writeStringToFile(
        file: File,
        content: String,
        charset: Charset
    ) {
        file.outputStream().writer(charset).use { writer ->
            writer.write(content)
        }
    }




}