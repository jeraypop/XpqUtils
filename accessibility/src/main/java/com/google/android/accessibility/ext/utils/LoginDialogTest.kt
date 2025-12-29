package com.google.android.accessibility.ext.utils

import android.content.Context
import android.os.CountDownTimer
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.generateCode
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.sendNotification
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginDialog(
    private val context: Context,
    private val onLogin: ((phone: String, code: String) -> Unit)? = null
) {

    private val dialog: AlertDialog

    private lateinit var etPhone: TextInputEditText
    private lateinit var etCode: TextInputEditText
    private lateinit var btnSendCode: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var cbAgree: CheckBox

    private var countDownTimer: CountDownTimer? = null
    private var mockSmsCode: String? = null
    private lateinit var etImgCode: TextInputEditText
    private lateinit var ivImgCode: TextView // 用 TextView 模拟图形验证码（工具库更方便）
    private var mockImgCode: String? = null

    init {
        val contentView = createContentView(context)
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("手机号登录")
            .setView(contentView)
            .setCancelable(true)
            .create()
    }

    fun show() {
        dialog.show()
    }

    fun dismiss() {
        countDownTimer?.cancel()
        dialog.dismiss()
    }

    // ================= UI 构建 =================

    private fun createContentView(ctx: Context): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 16), dp(ctx, 24), dp(ctx, 8))
        }

        // 手机号
        val phoneLayout = TextInputLayout(ctx).apply {
            hint = "手机号"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        etPhone = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            filters = arrayOf(InputFilter.LengthFilter(11))
        }
        phoneLayout.addView(etPhone)
        container.addView(phoneLayout, lpMatch(ctx))

        // ================= 图形验证码行 =================

        val imgCodeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val imgCodeLayout = TextInputLayout(ctx).apply {
            hint = "图形验证码"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }

        etImgCode = TextInputEditText(ctx).apply {
            filters = arrayOf(InputFilter.LengthFilter(4))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        imgCodeLayout.addView(etImgCode)

        ivImgCode = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            setBackgroundResource(android.R.drawable.editbox_background)
            setOnClickListener {
                refreshImgCode()
            }
        }

        imgCodeRow.addView(
            imgCodeLayout,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        imgCodeRow.addView(
            ivImgCode,
            LinearLayout.LayoutParams(dp(ctx, 88), dp(ctx, 48))
        )

        container.addView(imgCodeRow, lpMatch(ctx))


        // 验证码行
        val codeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val codeLayout = TextInputLayout(ctx).apply {
            hint = "验证码"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        etCode = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        codeLayout.addView(etCode)

        btnSendCode = TextView(ctx).apply {
            text = "获取验证码"
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            setTextColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
        }

        codeRow.addView(
            codeLayout,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        codeRow.addView(btnSendCode)

        container.addView(codeRow, lpMatch(ctx))

        // 登录按钮
        btnLogin = MaterialButton(ctx).apply {
            text = "登录 / 注册"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 16)
            }
        }
        container.addView(btnLogin)

        // 协议
        cbAgree = CheckBox(ctx).apply {
            text = "我已阅读并同意《用户协议》《隐私政策》"
        }
        container.addView(cbAgree)
        refreshImgCode()
        initListener()

        return container
    }

    // ================= 事件 =================
    private fun refreshImgCode() {
        mockImgCode = generateCode(4)
        ivImgCode.text = mockImgCode
    }

    private fun initListener() {

        btnSendCode.setOnClickListener {
            val phone = etPhone.text?.toString().orEmpty()
            if (!isPhoneValid(phone)) {
                toast("请输入正确的手机号")
                return@setOnClickListener
            }

            // TODO 发送验证码接口
            val code = generateCode(6)
            sendNotification(code =  code)
            // 可选：存起来用于校验
            mockSmsCode = code
            startCountDown()
        }

        btnLogin.setOnClickListener {
            val phone = etPhone.text?.toString().orEmpty()
            val code = etCode.text?.toString().orEmpty()
            val imgCode = etImgCode.text?.toString().orEmpty()
            if (!cbAgree.isChecked) {
                toast("请先同意用户协议")
                return@setOnClickListener
            }
            if (!isPhoneValid(phone)) {
                toast("手机号错误")
                return@setOnClickListener
            }

            if (imgCode != mockImgCode) {
                toast("图形验证码错误")
                refreshImgCode()
                return@setOnClickListener
            }

            if (code != mockSmsCode) {
                toast("短信验证码错误")
                return@setOnClickListener
            }

            onLogin?.invoke(phone, code)
            dismiss()
        }
    }

    // ================= 业务 =================

    private fun startCountDown() {
        btnSendCode.isEnabled = false
        countDownTimer?.cancel()

        countDownTimer = object : CountDownTimer(5_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                btnSendCode.text = "${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                btnSendCode.text = "获取验证码"
                btnSendCode.isEnabled = true
            }
        }.start()
    }

    private fun isPhoneValid(phone: String): Boolean {
        return phone.matches(Regex("^1[3-9]\\d{9}$"))
    }

    // ================= 工具 =================

    private fun lpMatch(ctx: Context) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(ctx, 12)
        }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
