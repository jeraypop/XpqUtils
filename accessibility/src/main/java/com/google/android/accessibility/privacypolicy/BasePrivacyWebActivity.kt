package com.google.android.accessibility.privacypolicy

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.accessibility.ext.R

/**
 * 协议展示页基类：标题栏（返回 + 标题）+ WebView + 加载中 + 失败重试。
 *
 * 子类只需给出「标题」「地址」「内置兜底页」三样东西。
 *
 * 相比旧实现修掉的问题：
 * 1. `javaScriptEnabled` 默认是 **false**，远程页大量依赖 JS（VitePress / Docsify / Cloudflare
 *    人机校验页…）会永远白屏 → 这里显式打开，同时开 `domStorageEnabled`。
 * 2. `shouldOverrideUrlLoading` 旧实现**两个分支都 return true**，把 http(s) 也拦了 →
 *    页内任何链接（条款里的《个人信息收集清单》、锚点）点了没反应。现在 http(s)/file/about/data
 *    交给 WebView 自己加载，其余（tel: mailto: market: weixin:）转交系统。
 * 3. 没有 `onReceivedError` → 断网/失败一片白。现在显示提示 + 重试；若宿主提供了对应
 *    asset 页，还会**自动降级到本地版本**（离线也能看到协议，审核要求）。
 * 4. 只能按系统返回键 → 标题栏左侧加了返回。
 */
abstract class BasePrivacyWebActivity : AppCompatActivity() {

    /** 标题栏文案 */
    protected abstract val pageTitleRes: Int

    /** 要加载的地址：http(s):// 与 file:///android_asset/xxx.html 都支持，裸文件名按 asset 处理 */
    protected abstract val pageUrl: String

    /** 内置兜底页的 asset 名（不含 `file:///android_asset/` 前缀） */
    protected abstract val fallbackAssetFileName: String

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var errorLayout: View? = null
    private var errorText: TextView? = null

    /** 当前实际加载的地址（重试 / 兜底判断用） */
    private var currentUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_xpq_privacy_web)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<TextView>(R.id.tv_page_title).setText(pageTitleRes)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        progressBar = findViewById(R.id.progress_bar)
        errorLayout = findViewById(R.id.layout_error)
        errorText = findViewById(R.id.tv_error)
        findViewById<TextView>(R.id.btn_retry).setOnClickListener { open(normalizeUrl(pageUrl)) }

        initWebView()
        open(normalizeUrl(pageUrl))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val container = findViewById<FrameLayout>(R.id.web_view_container)
        // 用 applicationContext 建 WebView，避免 WebView 长期持有 Activity 造成泄漏（沿用旧实现）
        val wv = WebView(applicationContext)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // 条款页里偶尔混有 http 图片/脚本，兼容模式避免整页被拦
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                showLoading()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                hideLoading()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // 只认主文档：图片/统计脚本挂掉不该把整页判死
                if (request?.isForMainFrame != true) return
                if (loadFallbackIfPossible()) return
                showError(getString(R.string.xpqprivacy_web_error))
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return true
                val scheme = Uri.parse(url).scheme?.lowercase()
                // 返回 false = 交给 WebView 自己加载
                if (scheme in WEB_SCHEMES) return false
                // 其余协议（tel: mailto: market: weixin: …）转交系统，失败就吞掉
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                return true
            }
        }
        container.addView(wv)
        webView = wv
    }

    private fun open(url: String) {
        currentUrl = url
        showLoading()
        webView?.visibility = View.VISIBLE
        webView?.loadUrl(url)
    }

    /**
     * 远程页加载失败时，若宿主确实打包了内置页就切过去。
     * 已经是本地页就不再兜底，避免失败 → 兜底 → 再失败的死循环。
     */
    private fun loadFallbackIfPossible(): Boolean {
        if (currentUrl.startsWith(XpqPrivacyDialog.ANDROID_ASSET)) return false
        val asset = fallbackAssetFileName.trim()
        if (asset.isEmpty()) return false
        if (!assetExists(asset)) return false
        open(XpqPrivacyDialog.ANDROID_ASSET + asset)
        return true
    }

    private fun assetExists(name: String): Boolean =
        runCatching { assets.open(name).close() }.isSuccess

    /** 裸文件名（如 `xpqlib_privacy.html`）当 asset 处理；带 scheme 的原样返回 */
    private fun normalizeUrl(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return XpqPrivacyDialog.ANDROID_ASSET + fallbackAssetFileName
        if (url.contains("://") || url.startsWith("about:") || url.startsWith("data:")) return url
        return XpqPrivacyDialog.ANDROID_ASSET + url.removePrefix("/")
    }

    private fun showLoading() {
        errorLayout?.visibility = View.GONE
        progressBar?.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        progressBar?.visibility = View.GONE
    }

    private fun showError(message: String) {
        progressBar?.visibility = View.GONE
        // WebView 自带的错误页很丑（白底 + 安卓小图标），藏起来只显示自己的提示
        webView?.visibility = View.INVISIBLE
        errorText?.text = message
        errorLayout?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        findViewById<FrameLayout>(R.id.web_view_container)?.removeAllViews()
        webView?.stopLoading()
        webView?.destroy()
        webView = null
    }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https", "file", "about", "data", "javascript")
    }
}
