package com.google.android.accessibility.inputmethod

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.inputmethod.FloatingImeWindow.Companion.floatingWindow
import com.google.android.accessibility.notification.NotificationListenerServiceAbstract.Companion.getAppName
import com.google.android.flexbox.FlexboxLayout


/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/4/22  9:53
 * Description:This is KeepAliveInputMethod
 */
class KeepAliveInputMethod : InputMethodService() {
    private lateinit var inputView: View
    private lateinit var flexPhrases: FlexboxLayout
    private lateinit var tvHint: TextView


    private val phrases = mutableListOf("恭喜发财","大吉大利", "新年快乐", "万事如意", "身体健康","福如东海","寿比南山")



    override fun onCreate() {
        super.onCreate()
        // 当输入法启动时，启动前台服务保活主功能
        AliveUtils.startFGAlive(enable = true)
        //从系统输入法选择器中 切换到该输入法
        imeContext = this
        Handler(Looper.getMainLooper()).postDelayed({
            // 延时执行的代码

        }, 6000L)


        if (canFloating() && floatingWindow != null) {
            Log.e("KeepAliveIME", "进来了")
            floatingWindow?.hide()
            floatingWindow = null
        }

        Log.e("KeepAliveIME", "输入法服务激活")
    }

    override fun onDestroy() {
        super.onDestroy()
        //从系统输入法选择器中 切换到其它输入法
        Log.e("KeepAliveIME", "输入法服务销毁")
        if (canFloating()) {
            floatingWindow = FloatingImeWindow()
            floatingWindow?.show()
        }
    }

    override fun onCreateInputView(): View {
        // 简单键盘布局
        inputView = layoutInflater.inflate(R.layout.ime_keyboard_layout, null)
        tvHint = inputView.findViewById(R.id.tv_input_hint)
        flexPhrases = inputView.findViewById<FlexboxLayout>(R.id.flex_phrases)
        // 初始化按钮事件
        setupButtons()

        return inputView
    }

    private fun setupButtons() {
        // 找到数字键所在的父布局
        val numberRow = inputView.findViewById<LinearLayout>(R.id.row_number_buttons) // 给这一行 LinearLayout 设置 id

        // 填充短语
        fun showPhrases() {
            flexPhrases.removeAllViews()
            for (phrase in phrases) {
                val btn = Button(this).apply {
                    text = phrase
                    setBackgroundColor(Color.TRANSPARENT)
                    setTextColor(Color.BLACK)
                    setOnClickListener {
                        currentInputConnection?.commitText(phrase, 1)
                        flexPhrases.visibility = View.GONE
                    }
                }
                // 添加到 FlexboxLayout
                flexPhrases.addView(btn)
            }
        }

        // 点击短语按钮展开/折叠
        inputView.findViewById<Button>(R.id.btn_phrase).setOnClickListener {
            if (flexPhrases.visibility == View.GONE) {
                flexPhrases.visibility = View.VISIBLE
                showPhrases()
            } else {
                flexPhrases.visibility = View.GONE
            }
        }
        // 字母
        listOf(
            R.id.btn_a to "A",
            R.id.btn_b to "B",
            R.id.btn_c to "C"
        ).forEach { (buttonId, char) ->
            val btn = inputView.findViewById<Button>(buttonId)
            btn.setOnClickListener {
                commitText(char)
                tvHint.text = char
            }
        }

        // 数字
        // 数字
        listOf(
            R.id.btn_0 to "0",
            R.id.btn_1 to "1",
            R.id.btn_2 to "2",
            R.id.btn_3 to "3",
            R.id.btn_4 to "4",
            R.id.btn_5 to "5",
            R.id.btn_6 to "6",
            R.id.btn_7 to "7",
            R.id.btn_8 to "8",
            R.id.btn_9 to "9"
        ).forEach { (buttonId, num) ->
            val btn = inputView.findViewById<Button>(buttonId)
            btn.setOnClickListener {
                commitText(num)
                tvHint.text = num
            }
        }

        // 回车
        inputView.findViewById<Button>(R.id.btn_enter).setOnClickListener {
            currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
            tvHint.text = "Enter"
        }

        // 删除
        inputView.findViewById<Button>(R.id.btn_del).setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
            tvHint.text = "Del"
        }
        // 切换输入法
        inputView.findViewById<Button>(R.id.btn_switch_ime).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            tvHint.text = "切换输入法"
        }

        // 用代码创建 btn_setting
        val btnSetting = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 48.dp()).apply {
                weight = 1f
                marginStart = 4.dp()
                marginEnd = 4.dp()
            }
            setImageResource(R.drawable.ic_settings_xpq)  // 图标
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            //background = null  // 避免背景拉伸
            contentDescription = "设置"

            setOnClickListener {
                // 打开设置
                val intent = Intent(context, ImeSettingsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }

        // 添加到父布局最前面
        numberRow.addView(btnSetting, 0)

    }
    // dp 转 px
    fun Int.dp(): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics).toInt()
    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun canFloating(): Boolean {
        val prefs = getSharedPreferences(XPQ_IME_SETTINGS, MODE_PRIVATE)
        return prefs.getBoolean(XPQ_IME_FLOATING, false)
    }

    companion object {
        var imeContext : Context? = null
        const val XPQ_IME_SETTINGS = "xpq_ime_settings"
        const val XPQ_IME_FLOATING = "xpq_ime_enable_floating_window"
        val className = KeepAliveInputMethod::class.java.name
        val xpqImeId: String
            get() = appContext.packageName + "/" + className



        fun ensureImeEnabledAndDefault(context: Context = appContext, imeId: String = xpqImeId) {
            val contentResolver = context.contentResolver
            val appName = getAppName(context.packageName)
            // 1️⃣ 检查是否启用
            val enabledImeList = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            ) ?: ""
            val isEnabled = enabledImeList.contains(imeId)

            if (!isEnabled) {
                // 未启用，提示并打开系统设置让用户启用
                AlertDialog.Builder(context)
                    .setTitle("输入法保活（可选）")
                    .setMessage("原理:系统一般不会清理当前使用的输入法,故,引入了此项设置," +
                            "\nPS: 其它保活措施到位的话此项设置可 不开启")
                    .setPositiveButton("去启用") { _, _ ->
                        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    }
                    .setNegativeButton("算了", null)
                    .show()
                return
            }

            // 2️⃣ 检查是否为默认
            val isDefault = isDefaultIme(context,imeId)

            if (!isDefault) {
                AlertDialog.Builder(context)
                    .setTitle("输入法保活(可选)")
                    .setMessage("原理:系统一般不会清理当前使用的输入法,故,引入了此项设置," +
                            "\nPS: 其它保活措施到位的话此项设置可 忽略")
                    .setPositiveButton("选择输入法") { _, _ ->
                        // 已启用但不是默认，弹出输入法选择器让用户切换
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                        AliveUtils.toast(msg = "请选择 $appName 作为默认输入法")
                    }
                    .setNegativeButton("算了", null)
                    .show()


                return
            }
            // 3️⃣ 已启用且为默认
            AliveUtils.toast(msg = "输入法保活已开启")
        }

        fun isDefaultIme(context: Context = appContext,imeId: String = xpqImeId): Boolean {
            val defaultIme = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: ""
            return defaultIme == imeId
        }


    }






}