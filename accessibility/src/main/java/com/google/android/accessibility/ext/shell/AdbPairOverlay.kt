package com.google.android.accessibility.ext.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.accessibility.ext.utils.AliveUtils

/**
 * 配对码浮窗。
 *
 * 系统「使用配对码配对设备」弹窗一离开设置就结束配对服务（本机实测：按 Home 后端口立即消失），
 * 所以在 App 里填码再点配对时端口已经不存在了。浮窗盖在设置页上方、不暂停设置页，
 * 用户可以在系统配对弹窗开着的同时把码填进来完成配对。
 */
object AdbPairOverlay {
    private const val TAG = "XpqAdbShell"

    @Volatile
    private var attached: View? = null

    @Volatile
    private var wm: WindowManager? = null

    @Volatile
    private var statusTv: TextView? = null

    @Volatile
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 持续发现到的配对端口；0 表示还没找到。 */
    @Volatile
    private var watchedPort = 0

    /** 发现线程的代次：自增即可让上一轮监听退出（关闭浮窗 / 配对成功后）。 */
    @Volatile
    private var watchToken = 0

    fun isShown(): Boolean = attached != null

    /** 显示浮窗；没有悬浮窗权限时返回 false。 */
    @SuppressLint("SetTextI18n")
    fun show(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        dismiss()
        val app = context.applicationContext
        val density = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), 0xFFDDDDDD.toInt())
            }
        }
        // 拖动状态：整窗任意空白处按住即可移动（按钮 / 输入框 / 可点链接仍各自响应点击）
        var lpRef: WindowManager.LayoutParams? = null
        val dragListener = object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                val lp = lpRef ?: return false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX
                        downRawY = e.rawY
                        startX = lp.x
                        startY = lp.y
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val metrics = app.resources.displayMetrics
                        // 用实际宽高，保证整窗都能停在屏幕内（首帧还没测量时退回估算值）
                        val winW = root.width.takeIf { it > 0 } ?: dp(320)
                        val winH = root.height.takeIf { it > 0 } ?: dp(120)
                        val maxX = ((metrics.widthPixels - winW) / 2).coerceAtLeast(0)
                        lp.x = (startX + (e.rawX - downRawX).toInt()).coerceIn(-maxX, maxX)
                        lp.y = (startY + (e.rawY - downRawY).toInt())
                            .coerceIn(0, (metrics.heightPixels - winH).coerceAtLeast(0))
                        runCatching { wm?.updateViewLayout(root, lp) }
                    }
                    else -> Unit
                }
                return true
            }
        }
        root.setOnTouchListener(dragListener)
        val title = TextView(app).apply {
            text = "WiFi 模式配对（可拖动）"
            textSize = 13f
            setTextColor(0xFF212121.toInt())
            setPadding(0, dp(2), 0, dp(2))
        }
        root.addView(title)
        val hintTv = TextView(app).apply {
            text = "正在查找系统配对服务…\n在设置里点「使用配对码配对设备」后会自动填好端口"
            textSize = 11f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(2), 0, 0)
        }
        root.addView(hintTv)
        val codeEt = EditText(app).apply {
            hint = "6 位配对码"
            setSingleLine(true)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFF212121.toInt())
            setHintTextColor(0xFF9E9E9E.toInt())
            minHeight = dp(40)
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        root.addView(codeEt, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // 端口默认自动发现（自建 mDNS），只有发现失败时才需要手填，所以收在「高级」里
        val portEt = EditText(app).apply {
            hint = "配对端口（配对弹窗里 : 后面的数字）"
            setSingleLine(true)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFF212121.toInt())
            setHintTextColor(0xFF9E9E9E.toInt())
            minHeight = dp(40)
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        val connPortEt = EditText(app).apply {
            hint = "连接端口（无线调试页 : 后面的数字）"
            setSingleLine(true)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFF212121.toInt())
            setHintTextColor(0xFF9E9E9E.toInt())
            minHeight = dp(40)
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        if (XpqAdbShell.rememberedConnectPort(app) > 0) {
            connPortEt.setText(XpqAdbShell.rememberedConnectPort(app).toString())
        }
        val advancedPanel = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            listOf(portEt, connPortEt).forEach { v ->
                addView(v, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }
        }
        val advancedToggle = TextView(app).apply {
            text = "高级：手填端口（自动发现失败时用）"
            textSize = 11f
            setTextColor(0xFF1A73E8.toInt())
            setPadding(0, dp(6), 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                advancedPanel.visibility =
                    if (advancedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        val status = TextView(app).apply {
            textSize = 11f
            setTextColor(0xFF757575.toInt())
            text = XpqAdbShell.status()
        }
        statusTv = status

        fun row(vararg views: View): LinearLayout = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEach {
                addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        fun button(label: String): Button = Button(app).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(0xFF212121.toInt())
            minHeight = dp(40)
            minimumHeight = 0
        }

        val COLOR_HINT = 0xFF757575.toInt()
        val COLOR_OK = 0xFF2E7D32.toInt()
        val COLOR_ERROR = 0xFFD32F2F.toInt()

        fun showHint(msg: String, color: Int = COLOR_HINT) {
            hintTv.post {
                if (attached !== root) return@post
                hintTv.setTextColor(color)
                hintTv.text = msg
            }
        }

        fun refreshStatus() {
            status.text = XpqAdbShell.status()
        }

        val pairBtn = button("配对").apply {
            setOnClickListener {
                val code = codeEt.text.toString().trim()
                val port = portEt.text.toString().trim().toIntOrNull()
                    ?: watchedPort
                // 照 Shizuku：端口不合法就指出来，而不是拿 0 去连
                if (port !in 1..65535) {
                    advancedPanel.visibility = View.VISIBLE
                    showHint("还没有配对端口：先在设置里打开「使用配对码配对设备」，浮窗会自动填好端口", COLOR_ERROR)
                    AliveUtils.toast(msg = "还没有配对端口")
                    return@setOnClickListener
                }
                if (code.length != 6 || code.toLongOrNull() == null) {
                    showHint("配对码是系统弹窗里的 6 位数字，请重新确认", COLOR_ERROR)
                    AliveUtils.toast(msg = "请先填 6 位配对码")
                    return@setOnClickListener
                }
                // 配对成功后直接连接：和 Shizuku 一样，用户只需要输一次码
                showHint("正在配对…")
                AliveUtils.toast(msg = "正在配对…")
                Thread {
                    val paired = runCatching {
                        XpqAdbShell.pair(app, "127.0.0.1", port, code)
                    }.getOrDefault(false)
                    if (paired) {
                        // 已配对，停止监听配对服务
                        watchToken++
                        watchedPort = 0
                        showHint("配对成功，正在连接…", COLOR_OK)
                    } else {
                        showHint(XpqAdbShell.lastError ?: "配对失败", COLOR_ERROR)
                        // 端口类失败多半是系统配对弹窗关了：重新开始等新端口
                        if (XpqAdbShell.lastPairErrorKind == XpqAdbShell.PairError.PORT) {
                            watchedPort = 0
                            portEt.post { portEt.setText("") }
                            advancedPanel.post { advancedPanel.visibility = View.VISIBLE }
                            startPairWatch(app, root, portEt, ::showHint)
                        }
                    }
                    val connected = paired && runCatching {
                        XpqAdbShell.connectAfterPair(app)
                    }.getOrDefault(false)
                    status.post {
                        refreshStatus()
                        when {
                            !paired -> AliveUtils.toast(msg = XpqAdbShell.lastError ?: "配对失败")
                            connected -> {
                                showHint("配对并连接成功，之后启动/重启都会自动重连", COLOR_OK)
                                AliveUtils.toast(msg = "配对并连接成功")
                            }
                            else -> {
                                showHint(
                                    "配对成功，但连接失败：${XpqAdbShell.lastError ?: "未知原因"}",
                                    COLOR_ERROR
                                )
                                AliveUtils.toast(msg = "配对成功，但连接失败")
                            }
                        }
                        // 配对成功后浮窗使命结束：停一下让用户看到结果，再自动关闭
                        if (paired) root.postDelayed({ dismiss() }, 800)
                    }
                }.start()
            }
        }
        val closeBtn = button("关闭").apply {
            setOnClickListener { dismiss() }
        }
        root.addView(
            row(pairBtn, closeBtn),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        root.addView(advancedToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(advancedPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        return try {
            val manager = app.getSystemService(WindowManager::class.java)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                dp(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // 不加 NOT_FOCUSABLE：要能收输入法输入；NOT_TOUCH_MODAL 让浮窗外的点击照常给系统弹窗
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(120)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
            lpRef = lp
            manager.addView(root, lp)
            wm = manager
            layoutParams = lp
            attached = root
            startPairWatch(app, root, portEt) { msg, color -> showHint(msg, color) }
            Log.i(TAG, "pair overlay attached")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "pair overlay failed", t)
            attached = null
            false
        }
    }

    /**
     * 提前、持续发现配对端口（对应 Shizuku 里「对话框一打开就常驻的 mDNS 发现」）。
     *
     * 配对服务只在系统「使用配对码配对设备」弹窗打开期间存在，而用户通常先开浮窗、
     * 再点系统里的选项，所以不能只查一次，要一直轮询到浮窗关闭或已配对。
     */
    private fun startPairWatch(
        app: Context,
        root: View,
        portEt: EditText,
        showHint: (String, Int) -> Unit,
    ) {
        val token = ++watchToken
        watchedPort = 0
        showHint("正在查找系统配对服务…\n在设置里点「使用配对码配对设备」后会自动填好端口", 0xFF757575.toInt())
        Thread {
            AdbMdns.watchPort(
                app,
                AdbMdns.PAIRING,
                isActive = { watchToken == token && attached === root },
            ) { port ->
                watchedPort = port
                portEt.post {
                    if (watchToken != token || attached !== root) return@post
                    portEt.setText(port.toString())
                    showHint("已找到配对端口 $port，填 6 位配对码点「配对」", 0xFF2E7D32.toInt())
                }
            }
        }.apply {
            isDaemon = true
            name = "xpq-adb-pair-watch"
        }.start()
    }

    fun dismiss() {
        // 让发现线程下一轮检查时退出
        watchToken++
        watchedPort = 0
        val view = attached ?: return
        runCatching { wm?.removeView(view) }
        attached = null
        wm = null
        statusTv = null
        layoutParams = null
    }
}
