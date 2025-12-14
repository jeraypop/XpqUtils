package com.google.android.accessibility.ext.activity

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.content.FileProvider
import com.android.accessibility.ext.databinding.ActivityExceptionReportxpqBinding
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class XpqExceptionReportActivity : XpqBaseActivity<ActivityExceptionReportxpqBinding>(
    bindingInflater = ActivityExceptionReportxpqBinding::inflate
) {

    private lateinit var exceptionReportBinding: ActivityExceptionReportxpqBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exceptionReportBinding = ActivityExceptionReportxpqBinding.inflate(layoutInflater)
        setContentView(exceptionReportBinding.root)
    }

    override fun onStart() {
        super.onStart()
        try {
            val file = File(getFilesDir(), "exception.txt")
            val exceptionMsg = readFileToString(file, StandardCharsets.UTF_8)
            exceptionReportBinding.exception.text = exceptionMsg
            exceptionReportBinding.export.setOnClickListener { v: View ->
                val intent = Intent(Intent.ACTION_SEND)
                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    appContext.packageName + ".xpqlibrary.FileProvider",
                    file
                )
                intent.setDataAndType(uri, contentResolver.getType(uri))
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.clipData = ClipData.newUri(contentResolver, "exception", uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, "导出"))
            }
        } catch (e: IOException) {
            AliveUtils.toast(msg = e.message.toString())
        }
    }

    @Throws(IOException::class)
    private fun readFileToString(file: File, charset: Charset): String {
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

    override fun initView_Xpq() {}

    override fun initData_Xpq() {}
}