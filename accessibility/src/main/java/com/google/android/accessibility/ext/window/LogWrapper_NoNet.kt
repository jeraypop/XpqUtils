package com.google.android.accessibility.ext.window

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import androidx.core.content.FileProvider
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.ViewDialogXpqcopyBinding
import com.android.accessibility.ext.databinding.ViewEditFileNameXpqBinding
import com.google.android.accessibility.ext.CoroutineWrapper
import com.google.android.accessibility.ext.task.getNowString
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.DigestUtils.md5Hex
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.XPQFileUtils
import com.google.android.accessibility.ext.utils.XPQFileUtils.writeStringToFile
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

object LogWrapper_NoNet {
    var logCache_NoNet = StringBuilder("")

    val logAppendValue_NoNet = MutableSharedFlow<Pair<String, String>>()

    fun String.logAppend_NoNet(): String {
        return logAppend_NoNet(this)
    }
    private val logLock = Mutex()
    private const val MAX_LINES = 1500
    fun logAppend_NoNet(msg: CharSequence): String {


        CoroutineWrapper.launch {
            logLock.withLock {
                val now = System.currentTimeMillis().getNowString()
                if (logCache_NoNet.isNotEmpty()) {
                    logCache_NoNet.append('\n')
                }

                logCache_NoNet.append(now)
                    .append('\n')
                    .append(msg)

                // 关键：只删除“超出的行”
                trimToMaxLines()
                logAppendValue_NoNet.emit(
                    Pair("\n$now\n$msg", logCache_NoNet.toString())
                )

            }

        }

        return msg.toString()
    }
    private fun trimToMaxLines() {
        var lineCount = 0

        // 先统计当前行数
        for (c in logCache_NoNet) {
            if (c == '\n') {
                lineCount++
            }
        }

        // 不超过，不处理
        if (lineCount <= MAX_LINES) return

        // 需要删除的行数
        var needRemove = lineCount - MAX_LINES
        var deleteIndex = 0

        // 从头开始，找到要删除到的位置
        for (i in logCache_NoNet.indices) {
            if (logCache_NoNet[i] == '\n') {
                needRemove--
                if (needRemove == 0) {
                    deleteIndex = i + 1
                    break
                }
            }
        }

        if (deleteIndex > 0) {
            logCache_NoNet.delete(0, deleteIndex)
        }
    }

    fun clearLog_NoNet() {
        logCache_NoNet = StringBuilder("")
        CoroutineWrapper.launch { logAppendValue_NoNet.emit(Pair("", "")) }
    }

    fun copyLogMethod_NoNet(numCount: Int = 9996) {
        val logContent = logCache_NoNet.toString()

        // 检查日志长度是否超过10000字符
        if (logContent.length > numCount) {
            // 需要显示对话框让用户选择操作方式
            Handler(Looper.getMainLooper()).post {
                showCopyOptionDialog_NoNet(logContent)
            }


        } else {
            // 直接复制到剪贴板
            copyLogToClipboard_NoNet(logContent)
        }
    }

    fun copyLogToClipboard_NoNet(logContent: String) {
        try {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Log Content", logContent)
            clipboard.setPrimaryClip(clip)
            AliveUtils.toast(msg = "已复制日志到剪贴板")
            OverlayLog.hide()
        } catch (e: Exception){
            AliveUtils.toast(msg = "复制日志出现错误!"+e.message)
        }

    }

    private fun showCopyOptionDialog_NoNet(logContent: String) {
        accessibilityService ?: return
        val s = "日志内容过长，可能无法直接通过微信,QQ等发送出去,建议通过txt文件的方式发送\n请选择操作方式"
        val binding = ViewDialogXpqcopyBinding.inflate(LayoutInflater.from(accessibilityService))
        binding.message.text = s

        val dialog = AlertDialog.Builder(accessibilityService)
            .setTitle("日志过长")
            .setMessage(s)
            //.setView(binding.root)
            .setNegativeButton("剪贴板"){ _, _ ->
                // 直接复制到剪贴板
                copyLogToClipboard_NoNet(logContent)
            }
            .setPositiveButton("txt文件") { _, _ ->
                OverlayLog.hide()
                shareLogFile_NoNet(logContent)
            }
            .setOnDismissListener {
                // 可以添加清理逻辑
            }
            .create()

        dialog.window?.attributes?.type = AssistsWindowManager.chooseWindowType()
        dialog.show()
    }


    fun showEditShareFileNameDialog_NoNet(strRegulation: String) {
        val service = accessibilityService ?: return

        // 🚨 保证在主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                showEditShareFileNameDialog_NoNet(strRegulation)
            }
            return
        }

        val binding =
            ViewEditFileNameXpqBinding.inflate(LayoutInflater.from(service)).apply {
                fileName.hint = md5Hex(strRegulation)
            }

        val dialog = AlertDialog.Builder(service)
            .setTitle("请输入文件名")
            .setView(binding.root)
            .setCancelable(false)
            .setNegativeButton(appContext.getString(R.string.cancel), null)
            .setPositiveButton(appContext.getString(R.string.ok)) { _, _ ->

                // ⚠️ 正按钮里的 IO 操作，切后台线程
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        XPQFileUtils.cleanDirectory(service.cacheDir)

                        val fileName =
                            binding.fileName.text.toString().trim().ifEmpty {
                                binding.fileName.hint.toString()
                            }

                        val file = File(service.cacheDir, "$fileName.txt")
                        writeStringToFile(file, strRegulation, StandardCharsets.UTF_8)

                        val uri = FileProvider.getUriForFile(
                            service,
                            "${appContext.packageName}.xpqlibrary.FileProvider",
                            file
                        )

                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            setDataAndType(uri, service.contentResolver.getType(uri))
                            putExtra(Intent.EXTRA_TEXT, strRegulation)
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(
                                service.contentResolver,
                                "sendlog",
                                uri
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        withContext(Dispatchers.Main) {
                            val chooser = Intent.createChooser(sendIntent, "分享").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            service.startActivity(chooser)
                        }

                    } catch (ex: IOException) {
                        withContext(Dispatchers.Main) {
                            AliveUtils.toast(msg = "生成分享文件时发生错误")
                        }
                    }
                }
            }
            .create()

        // ⭐ 兼容模式：无障碍可用用 accessibility overlay，否则回退普通悬浮窗
        dialog.window?.setType(AssistsWindowManager.chooseWindowType())
        dialog.show()
    }
    fun shareLogFile_NoNet(strRegulation: String) {
        val service = appContext ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                XPQFileUtils.cleanDirectory(service.cacheDir)

                val fileName = "sendMsgLog"

                val file = File(service.cacheDir, "$fileName.txt")
                writeStringToFile(file, strRegulation, StandardCharsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    service,
                    "${service.packageName}.xpqlibrary.FileProvider",
                    file
                )

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    setDataAndType(uri, service.contentResolver.getType(uri))
                    putExtra(Intent.EXTRA_TEXT, strRegulation)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(
                        service.contentResolver,
                        "sendlog",
                        uri
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    val chooser = Intent.createChooser(sendIntent, "分享").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(chooser)
                }

            } catch (ex: IOException) {
                withContext(Dispatchers.Main) {
                    AliveUtils.toast(msg = "生成发送日志时发生错误")
                }
            }
        }
    }

}