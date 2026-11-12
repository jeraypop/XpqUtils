package com.lygttpod.android.auto.adb

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.accessibility.ext.shell.XpqAdbShell

/**
 * 内置 ADB（无线调试）接入验证页。
 *
 * 只存在于 demo app，不会随 accessibility 库发布。
 *
 * 两个端口不是同一个，别填反：
 *  - 配对端口：点「使用配对码配对设备」后弹窗里显示的端口
 *  - 连接端口：无线调试主页「IP 地址和端口」里的端口
 *
 * 看到 `uid=2000(shell)` 即验证通过。
 */
class AdbShellTestActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var etHost: EditText
    private lateinit var etPairPort: EditText
    private lateinit var etCode: EditText
    private lateinit var etConnectPort: EditText
    private lateinit var tvOutput: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        tvOutput = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        fun field(hintText: String, def: String = "", numeric: Boolean = false) = EditText(this).apply {
            hint = hintText
            setText(def)
            setSingleLine()
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }

        etHost = field("host", "127.0.0.1")
        etPairPort = field("配对端口（配对弹窗里的）", numeric = true)
        etCode = field("6 位配对码", numeric = true)
        etConnectPort = field("连接端口（无线调试主页的）", numeric = true)

        fun log(text: String) = ui.post { tvOutput.append("$text\n") }
        fun runAsync(block: () -> Unit) {
            Thread {
                runCatching(block).onFailure { log("异常: ${it.javaClass.simpleName}: ${it.message}") }
            }.start()
        }

        fun row(vararg children: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            children.forEach {
                addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

        root.addView(etHost)
        root.addView(etPairPort)
        root.addView(etCode)
        root.addView(etConnectPort)
        root.addView(
            row(
                button("自动找配对端口") {
                    runAsync {
                        val port = XpqAdbShell.discoverPairPort(this)
                        ui.post { if (port > 0) etPairPort.setText(port.toString()) }
                        log(if (port > 0) "配对端口=$port" else "没找到配对服务（先点「使用配对码配对设备」）")
                    }
                },
                button("自动找连接端口") {
                    runAsync {
                        val port = XpqAdbShell.discoverConnectPort(this)
                        ui.post { if (port > 0) etConnectPort.setText(port.toString()) }
                        log(if (port > 0) "连接端口=$port" else "没找到连接服务（确认无线调试已打开）")
                    }
                },
            )
        )
        root.addView(
            row(
                button("配对") {
                    val code = etCode.text.toString().trim()
                    if (code.isEmpty()) {
                        log("请先填 6 位配对码（配对端口可留空自动发现）")
                        return@button
                    }
                    val pairPort = etPairPort.text.toString().toIntOrNull() ?: 0
                    runAsync {
                        log("配对中…")
                        val ok = XpqAdbShell.pair(this, etHost.text.toString().trim(), pairPort, code)
                        log(if (ok) "✓ 配对成功，点「自动找连接端口」后点「连接」" else "✗ 配对失败：${XpqAdbShell.status()}")
                    }
                },
                button("连接") {
                    val port = etConnectPort.text.toString().toIntOrNull() ?: 0
                    runAsync {
                        log("连接中…")
                        val ok = XpqAdbShell.connect(this, etHost.text.toString().trim(), port)
                        log(if (ok) "✓ 已连接（shell 身份）" else "✗ 连接失败：${XpqAdbShell.status()}")
                    }
                },
            )
        )
        root.addView(
            row(
                button("执行 id") {
                    runAsync {
                        val r = XpqAdbShell.exec("id")
                        log(if (r == null) "✗ 未连接" else "exit=${r.exitCode} ${r.output.trim()}")
                    }
                },
                button("断开") {
                    XpqAdbShell.disconnect()
                    log("已断开")
                },
            )
        )
        root.addView(button("查看状态") { log(XpqAdbShell.status()) })
        // 验证模式对话框：应能看到第三项「内置 ADB 模式(无需 Shizuku)」
        root.addView(
            button("打开模式对话框") {
                com.google.android.accessibility.ext.acc.XpqAcc.showEngineModeDialog(this@AdbShellTestActivity)
            }
        )
        // 验证 P2：库内统一 shell 入口是否真的走了内置 ADB
        root.addView(
            button("库内 exec（InvisibleAutomation）") {
                runAsync {
                    val r = com.google.android.accessibility.uiautomation.engine.InvisibleAutomation
                        .exec("id")
                    log(
                        if (r == null) "✗ 库内 exec 返回 null"
                        else "库内 exec exit=${r.exitCode} ${r.stdout.trim()}"
                    )
                }
            }
        )
        // 验证 Sidecar：内置 ADB 拉起 shell 身份常驻进程，并经 ContentProvider 交回 binder
        root.addView(
            row(
                button("Sidecar 自检") {
                    runAsync {
                        log("Sidecar 启动中…")
                        val ok = com.google.android.accessibility.ext.shell.XpqSidecar.ensureStarted(this)
                        log(
                            "ensureStarted=$ok uid=" +
                                    com.google.android.accessibility.ext.shell.XpqSidecar.sidecarUid +
                                    " err=" + com.google.android.accessibility.ext.shell.XpqSidecar.lastError
                        )
                        if (ok) {
                            val r = com.google.android.accessibility.ext.shell.XpqSidecar.exec("id")
                            log(
                                if (r == null) "✗ sidecar exec 返回 null"
                                else "sidecar exec exit=${r.exitCode} ${r.output.trim()}"
                            )
                        }
                    }
                },
                button("Sidecar 状态") {
                    log(com.google.android.accessibility.ext.shell.XpqSidecar.status())
                },
            )
        )
        root.addView(
            button("读取 root（UiAutomation）") {
                runAsync {
                    val n = com.google.android.accessibility.ext.acc.XpqAcc.rootInActiveWindow()
                    log(
                        if (n == null) "✗ root=null（UiAutomation 通道未连接）"
                        else "root pkg=${n.packageName} class=${n.className}"
                    )
                }
            }
        )
        root.addView(
            button("虚拟屏自检（建屏/释放）") {
                Thread {
                    val vd = com.google.android.accessibility.ext.vdisplay.XpqVDisplay
                    val session = runCatching {
                        kotlinx.coroutines.runBlocking {
                            vd.create(this@AdbShellTestActivity, 720, 1280, 320)
                        }
                    }.getOrNull()
                    log(
                        "vdisplay backend=${vd.backendName} " + if (session == null) {
                            "✗ ${vd.lastCreateError}"
                        } else {
                            "✓ display=${session.displayId} ${session.width}x${session.height}"
                        }
                    )
                    if (session != null) {
                        val launched = runCatching { session.launch("com.android.settings") }.getOrDefault(-1)
                        log("vdisplay launch settings -> $launched（0=成功）")
                        Thread.sleep(2_000)
                        runCatching { session.endTasks() }
                        session.release()
                        log("vdisplay released")
                    }
                }.start()
            }
        )
        root.addView(ScrollView(this).apply { addView(tvOutput) })

        setContentView(root)
    }
}
