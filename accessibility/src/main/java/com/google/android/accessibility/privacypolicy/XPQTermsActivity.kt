package com.google.android.accessibility.privacypolicy

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.AppInfoUtil
import com.google.android.accessibility.privacypolicy.XpqPrivacyDialog.Companion.LANGUAGE_CN
import com.google.android.accessibility.privacypolicy.XpqPrivacyDialog.Companion.xpqterms

class XPQTermsActivity : AppCompatActivity() {
    private var web_view_container: FrameLayout? = null
    private var web_view: WebView? = null



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_xpqterms)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        initView()
    }

    private fun initView() {
        web_view_container = findViewById(R.id.web_view_container)
        web_view = WebView(applicationContext)
        
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        web_view?.layoutParams = params
        
        web_view?.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (!url.isNullOrEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    return true
                }
                return true
            }
        }
        
        // 动态添加WebView，解决在xml引用WebView持有Activity的Context对象，导致内存泄露
        web_view_container?.addView(web_view)

        // 获取设备语言 
        val language = AppInfoUtil.getLanguageTag()

        if (LANGUAGE_CN == language) {
            // 中文语言处理逻辑
        } else {
            // 其他语言处理逻辑
        }

        web_view?.loadUrl(xpqterms)
    }

    override fun onDestroy() {
        super.onDestroy()
        web_view_container?.removeAllViews()
        web_view?.destroy()
    }
}
