import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.google.android.accessibility.ext.utils.AliveUtils

class WebDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_URL = "arg_url"

        fun newInstance(url: String): WebDialogFragment {
            return WebDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                }
            }
        }
    }

    private var webView: WebView? = null
    private var isDesktopMode = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val dialog = Dialog(
            context,
            android.R.style.Theme_Material_Light_NoActionBar
        )

        // =========================
        // Root Layout
        // =========================
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ② ✅ 就在这里：处理系统栏 Insets（关键点）
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // =========================
        // WebView
        // =========================
        webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(webView)
        // =================================================
        // ✅【就在这里】加入“桌面/手机”切换按钮
        // =================================================
        val switchBtn = Button(context).apply {
            text = "无法拉起支付宝?\n点我切换成电脑模式"
            alpha = 0.8f
            setOnClickListener {
                toggleDesktopMode()
                AliveUtils.toast(msg = "已切换为 ${if (isDesktopMode) "电脑" else "手机"}模式")
            }
        }
        switchBtn.setBackgroundColor(Color.parseColor("#66000000"))
        switchBtn.setTextColor(Color.WHITE)
        switchBtn.setPadding(24, 12, 24, 12)

        val btnLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            bottomMargin = 100
            rightMargin = 100
        }

        root.addView(switchBtn, btnLp)
        // =================================================



        dialog.setContentView(root)

        initWebView(webView!!)

        arguments?.getString(ARG_URL)?.takeIf { it.isNotBlank() }?.let {
            webView?.loadUrl(it)
        }

        // 返回键优先 WebView
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK
                && event.action == KeyEvent.ACTION_UP
                && webView?.canGoBack() == true
            ) {
                webView?.goBack()
                true
            } else {
                false
            }
        }

        return dialog
    }

    // =========================
    // WebView 初始化
    // =========================
    private fun initWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
        }

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return handleUrl(view.context, request.url.toString())
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrl(view.context, url)
            }
        }
    }

    // =========================
    // URL 处理（网页 → App）
    // =========================
    private fun handleUrl(context: Context, url: String): Boolean {
        return try {
            when {
                url.startsWith("intent://") -> {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    context.startActivity(intent)
                    true
                }

                url.startsWith("weixin://")
                        || url.startsWith("alipays://")
                        || url.startsWith("qq://") -> {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                }

                else -> false // WebView 正常加载
            }
        } catch (e: Exception) {
            true
        }
    }

    // =========================
    // 切换电脑 / 手机模式
    // =========================
    fun toggleDesktopMode() {
        webView?.let {
            isDesktopMode = !isDesktopMode
            applyDesktopMode(it, isDesktopMode)
        }
    }

    private fun applyDesktopMode(webView: WebView, enable: Boolean) {
        val settings = webView.settings

        if (enable) {
            val desktopUA =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36"

            settings.userAgentString = desktopUA
        } else {
            settings.userAgentString = null // 恢复系统 UA
        }

        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.reload()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView?.apply {
            loadUrl("about:blank")
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
    }
}
