package com.google.android.accessibility.ext.window

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.ViewDialogXpqcopyBinding
import com.android.accessibility.ext.databinding.ViewEditFileNameXpqBinding
import com.google.android.accessibility.ext.CoroutineWrapper
import com.google.android.accessibility.ext.task.getNowString
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.AppInfoUtil
import com.google.android.accessibility.ext.utils.DigestUtils.md5Hex
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.XPQFileUtils
import com.google.android.accessibility.ext.utils.XPQFileUtils.writeStringToFile
import com.google.android.accessibility.ext.wcapi.getWCField
import com.google.android.accessibility.ext.wcapi.restoreAllIllusion
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object LogWrapper {
    var logCache = StringBuilder("")

    val logAppendValue = MutableSharedFlow<Pair<String, String>>()

    fun String.logAppend(): String {
        return logAppend(this)
    }

    /*    fun logAppend(msg: CharSequence): String {
            if (logCache.isNotEmpty()) {
                logCache.append("\n")
            }
            if (logCache.length > 5000) {
                logCache.delete(0, logCache.length - 5000)
            }
            logCache.append(TimeUtils.getNowString())
            logCache.append("\n")
            logCache.append(msg)
            CoroutineWrapper.launch {
                logAppendValue.emit(Pair("\n${TimeUtils.getNowString()}\n$msg", logCache.toString()))
            }
            return msg.toString()
        }*/
    private val logLock = Mutex()
    private const val MAX_LINES = 1500
    fun logAppend(msg: CharSequence): String {


        CoroutineWrapper.launch {
            logLock.withLock {
                val now = System.currentTimeMillis().getNowString()
                if (logCache.isNotEmpty()) {
                    logCache.append('\n')
                }

                logCache.append(now)
                    .append('\n')
                    .append(msg)

                // 关键：只删除“超出的行”
                trimToMaxLines()
                logAppendValue.emit(
                    Pair("\n$now\n$msg", logCache.toString())
                )

            }

        }

        return msg.toString()
    }
    private fun trimToMaxLines() {
        var lineCount = 0

        // 先统计当前行数
        for (c in logCache) {
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
        for (i in logCache.indices) {
            if (logCache[i] == '\n') {
                needRemove--
                if (needRemove == 0) {
                    deleteIndex = i + 1
                    break
                }
            }
        }

        if (deleteIndex > 0) {
            logCache.delete(0, deleteIndex)
        }
    }



/*    fun logAppend(msg: CharSequence): String {
        if (logCache.isNotEmpty()) {
            logCache.append("\n")
        }

        // 添加新日志前检查行数并清理
        val lines = logCache.split('\n')
        if (lines.size > 1000) {
            val startIndex = lines[1000].let { logCache.indexOf(it) + it.length + 1 }
            logCache.delete(0, startIndex)
        }

        logCache.append(TimeUtils.getNowString())
        logCache.append("\n")
        logCache.append(msg)

        CoroutineWrapper.launch {
            logAppendValue.emit(Pair("\n${TimeUtils.getNowString()}\n$msg", logCache.toString()))
        }

        return msg.toString()
    }*/


    fun clearLog() {
        logCache = StringBuilder("")
        CoroutineWrapper.launch { logAppendValue.emit(Pair("", "")) }
    }

    fun copyLogMethod(numCount: Int = 9996) {
        val logContent = logCache.toString()

        // 检查日志长度是否超过10000字符
        if (logContent.length > numCount) {
            // 需要显示对话框让用户选择操作方式
            Handler(Looper.getMainLooper()).post {
                showCopyOptionDialog(logContent)
            }


        } else {
            // 直接复制到剪贴板
            copyLogToClipboard(logContent)
        }
    }

    fun copyLogToClipboard(logContent: String) {
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

    private fun showCopyOptionDialog(logContent: String) {
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
                copyLogToClipboard(logContent)
            }
            .setPositiveButton("txt文件") { _, _ ->
                OverlayLog.hide()
                shareLogFile(logContent)
            }
            .setOnDismissListener {
                // 可以添加清理逻辑
            }
            .create()

        dialog.window?.attributes?.type = AssistsWindowManager.chooseWindowType()
        dialog.show()
    }


    fun showEditShareFileNameDialog(strRegulation: String) {
        val service = accessibilityService ?: return

        // 🚨 保证在主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                showEditShareFileNameDialog(strRegulation)
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
    fun shareLogFile(strRegulation: String) {
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
    @JvmOverloads
    @JvmStatic
    fun showUploadDialog(
        context: Context? = accessibilityService,
        uploadMsg: String = logCache.toString(),
        path: String = "运行日志",
        token: String = getWCField[7].first.restoreAllIllusion(),
        owner: String = "mutoupiaoliu",
        repo: String = "log"
    ) {
        context ?: return
        // 显示上传对话框 必须在主线程
        Handler(Looper.getMainLooper()).post {
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.xpq_upload_log)
                .setMessage(R.string.xpq_message_upload_log)
                .setPositiveButton(R.string.ok) { _, _ ->
                    uploadLogToGitee(
                        context = context,
                        uploadMsg = uploadMsg,
                        path = path,
                        token = token,
                        owner = owner,
                        repo = repo
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
            // 只有非 Activity Context 才设置窗口类型（兼容：无障碍可用用 accessibility overlay，否则普通悬浮窗）
            if (context.findActivity() == null) {
                dialog.window?.setType(AssistsWindowManager.chooseWindowType())
            }
            dialog.show()
        }

    }

    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return null
    }
   @JvmOverloads
   @JvmStatic
    fun uploadLogToGitee(
        context: Context? = accessibilityService,
        uploadMsg: String = logCache.toString(),
        path: String = "异常日志",
        token: String = getWCField[7].first.restoreAllIllusion(),
        owner: String = "mutoupiaoliu",
        repo: String = "log",
        showToast: Boolean = true
    ) {
        context ?: return
       if (uploadMsg.isEmpty()){
           if (showToast) AliveUtils.toast(msg = "日志为空，取消上传")
            return
        }
       if (showToast)AliveUtils.toast(msg = context.getString(R.string.xpq_uploading_log))
        val appName = AppInfoUtil.getAppName(context, context.packageName)
       val originPath = appName + "/" + path
        Thread {
            try {
                val timestamp = java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())

                val fileName =
                    "${path}--$timestamp.txt"

                val logFile = File(context.cacheDir, fileName)

                FileOutputStream(logFile).use {
                    it.write(uploadMsg.toByteArray(Charsets.UTF_8))
                }

                val fileBytes = logFile.readBytes()

                val base64Content = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP)

                val encodedPath = originPath.split("/")
                    .joinToString("/") {
                        URLEncoder.encode(it, "UTF-8")
                            .replace("+", "%20")
                    }

                val encodedFileName = URLEncoder.encode(fileName, "UTF-8")
                    .replace("+", "%20")

                val url = java.net.URL(
                    "https://gitee.com/api/v5/repos/$owner/$repo/contents/$encodedPath/$encodedFileName"
                )

                val conn = url.openConnection() as java.net.HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                )
                conn.doOutput = true

                val body = org.json.JSONObject().apply {
                    put("access_token", token)
                    put("content", base64Content)
                    put(
                        "message",
                        "${AppInfoUtil.getAppName(context, context.packageName)}运行日志: $fileName"
                    )
                }

                val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)

                conn.setFixedLengthStreamingMode(bodyBytes.size)

                conn.outputStream.use {
                    it.write(bodyBytes)
                    it.flush()
                }

                val responseCode = conn.responseCode

                if (responseCode == java.net.HttpURLConnection.HTTP_CREATED ||
                    responseCode == java.net.HttpURLConnection.HTTP_OK
                ) {
                    if (showToast)AliveUtils.toast(
                        msg = context.getString(R.string.xpq_upload_success)
                    )
                } else {
                    if (showToast)AliveUtils.toast(
                        msg = context.getString(R.string.xpq_upload_error)
                    )

                    // 建议打印错误信息，方便排查
                    conn.errorStream?.bufferedReader()?.use {
                        Log.e("Gitee", it.readText())
                    }
                }

                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()

                if (showToast)AliveUtils.toast(
                    msg = context.getString(R.string.xpq_upload_error)
                )
            }
        }.start()
    }

}