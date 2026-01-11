import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.*
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
import kotlin.math.abs

class WebDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_URL = "arg_url"

        private const val SP_NAME = "web_dialog_float_btn"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        fun newInstance(url: String): WebDialogFragment =
            WebDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                }
            }
    }

    private var webView: WebView? = null
    private var isDesktopMode = false

    // =========================
    // Dialog
    // =========================
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val dialog = Dialog(
            context,
            android.R.style.Theme_Material_Light_NoActionBar
        )

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // WebView
        webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(webView)

        // =========================
        // 胶囊按钮
        // =========================
        val capsuleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(Color.parseColor("#66000000"))
        }

        val switchBtn = Button(context).apply {
            text = "无法拉起支付宝?\n点我切换成电脑模式"
            alpha = 0.85f
            setTextColor(Color.WHITE)
            setPadding(36, 18, 36, 18)
            background = capsuleBg
        }

        val btnLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        root.addView(switchBtn, btnLp)

        attachDragAndClick(root, switchBtn)

        dialog.setContentView(root)

        initWebView(webView!!)

        arguments?.getString(ARG_URL)?.takeIf { it.isNotBlank() }?.let {
            webView?.loadUrl(it)
        }

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
    // 拖动 + 点击 + 持久化
    // =========================
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndClick(root: FrameLayout, btn: View) {
        val context = requireContext()
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false

        // 等 layout 完成后恢复位置
        root.post {
            val insets = ViewCompat.getRootWindowInsets(root)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())

            val minY = insets?.top?.toFloat() ?: 0f
            val maxY = root.height - btn.height - (insets?.bottom ?: 0)

            val x = sp.getFloat(KEY_X, 24f)
            val y = sp.getFloat(KEY_Y, minY + 24f)

            btn.x = x.coerceIn(0f, root.width - btn.width.toFloat())
            btn.y = y.coerceIn(minY, maxY.toFloat())
        }

        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = v.x
                    startY = v.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }

                    if (dragging) {
                        val insets = ViewCompat.getRootWindowInsets(root)
                            ?.getInsets(WindowInsetsCompat.Type.systemBars())

                        val minY = insets?.top?.toFloat() ?: 0f
                        val maxY =
                            root.height - v.height - (insets?.bottom ?: 0)

                        v.x = (startX + dx).coerceIn(
                            0f,
                            root.width - v.width.toFloat()
                        )
                        v.y = (startY + dy).coerceIn(
                            minY,
                            maxY.toFloat()
                        )
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        sp.edit()
                            .putFloat(KEY_X, v.x)
                            .putFloat(KEY_Y, v.y)
                            .apply()
                    } else {
                        toggleDesktopMode()
                        AliveUtils.toast(
                            msg = "已切换为 ${if (isDesktopMode) "电脑" else "手机"}模式"
                        )
                    }
                    true
                }

                else -> false
            }
        }
    }

    // =========================
    // WebView
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
            ): Boolean = handleUrl(view.context, request.url.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleUrl(view.context, url)
        }
    }

    private fun handleUrl(context: Context, url: String): Boolean {
        return try {
            when {
                url.startsWith("intent://") -> {
                    context.startActivity(
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    )
                    true
                }

                url.startsWith("weixin://")
                        || url.startsWith("alipays://")
                        || url.startsWith("qq://") -> {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    )
                    true
                }

                else -> false
            }
        } catch (e: Exception) {
            true
        }
    }

    // =========================
    // 桌面 / 手机模式
    // =========================
    fun toggleDesktopMode() {
        webView?.let {
            isDesktopMode = !isDesktopMode
            applyDesktopMode(it, isDesktopMode)
        }
    }

    private fun applyDesktopMode(webView: WebView, enable: Boolean) {
        val settings = webView.settings

        settings.userAgentString =
            if (enable)
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            else null

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
