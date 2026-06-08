package com.google.android.accessibility.ext.utils

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.CountDownTimer
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.fragment.SensitiveNotificationBottomSheet
import com.google.android.accessibility.ext.utils.AliveUtils.shouxianzhi
import com.google.android.accessibility.ext.utils.AliveUtils.toast
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.generateCode
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.sendNotification
import com.google.android.accessibility.ext.utils.verificationcode.LoginConfig
import com.google.android.accessibility.selecttospeak.accessibilityService
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hjq.permissions.permission.PermissionLists

class LoginDialog(
    private val context: Context,
    private val hasAccessibility: Boolean = false,
    private val onLogin: ((phone: String, code: String) -> Unit)? = null
) {

    private val dialog: AlertDialog

    private lateinit var etPhone: TextInputEditText
    private lateinit var etCode: TextInputEditText
    private lateinit var btnSendCode: TextView
    private lateinit var btnLogin: Button
    private lateinit var cbAgree: CheckBox

    private var countDownTimer: CountDownTimer? = null
    private var mockSmsCode: String? = null
    private lateinit var etImgCode: TextInputEditText
    private lateinit var ivImgCode: TextView // 用 TextView 模拟图形验证码（工具库更方便）
    private var mockImgCode: String? = null
    private lateinit var helpContainer: LinearLayout
    private lateinit var btnHelp: TextView

    private lateinit var cbAutoFill: CheckBox
    private lateinit var cbAutoShowCodeFloat: CheckBox
    private lateinit var cbVoiceRead: CheckBox
    private lateinit var cbVoiceReadTwo: CheckBox

    private lateinit var radioGroup: RadioGroup
    private lateinit var rbScheme1: RadioButton
    private lateinit var rbScheme2: RadioButton


    init {
        val contentView = createContentView(context)
        dialog =  createDialogBuilder(context)
            .setTitle(context.getString(R.string.phoneloginxpq))
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
    private fun createDialogBuilder(ctx: Context): AlertDialog.Builder {
        return if (isMaterialTheme(ctx)) {
            // Material 主题：安全
            MaterialAlertDialogBuilder(ctx)
        } else {
            // 非 Material：自动降级
            AlertDialog.Builder(ctx)
        }
    }

    private fun isMaterialTheme(ctx: Context): Boolean {
        val typedValue = TypedValue()
        return ctx.theme.resolveAttribute(
            com.google.android.material.R.attr.materialButtonStyle,
            typedValue,
            true
        )
    }

    private fun getPrimaryColorByTheme(ctx: Context): Int {
        return if (isMaterialTheme(ctx)){
            MaterialColors.getColor(
                ctx,
                android.R.attr.colorPrimary,
                Color.GRAY
            )
        }else{
            ContextCompat.getColor(ctx, R.color.colorPrimary)
        }
    }

    private fun createLoginButton(ctx: Context): View {
        val primaryColor = getPrimaryColorByTheme(ctx)

        return if (isMaterialTheme(ctx)) {
            // ===== Material 主题 =====
            MaterialButton(ctx).apply {
                text = context.getString(R.string.logindexpq)
                setTextColor(Color.WHITE)
                setBackgroundColor(primaryColor)

                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(ctx, 8)
                }
            }
        } else {
            // ===== 非 Material 主题，自动降级 =====
            AppCompatButton(ctx).apply {
                text = context.getString(R.string.logindexpq)
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundColor(primaryColor)
                setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))

                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(ctx, 8)
                }
            }
        }
    }


    // ================= UI 构建 =================

    private fun createContentView(ctx: Context): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 16), dp(ctx, 24), dp(ctx, 8))
        }

        // ================= 功能设置 =================


        val settingsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 12)
            }
        }

        cbAutoFill = CheckBox(ctx).apply {
            text = "自动填充验证码开关"
            textSize = 16f
            isChecked = LoginConfig.isAutoFillEnabled(ctx)

            setOnCheckedChangeListener { _, checked ->
                LoginConfig.setAutoFillEnabled(ctx, checked)
            }
        }

        cbAutoShowCodeFloat = CheckBox(ctx).apply {
            text = "验证码悬浮窗开关"
            textSize = 16f
            isChecked = LoginConfig.isAutoShowCodeFloat(ctx)

            setOnCheckedChangeListener { _, checked ->
                LoginConfig.setAutoShowCodeFloat(ctx, checked)
            }
        }

        cbVoiceRead = CheckBox(ctx).apply {
            text = "语音播报验证码开关"
            textSize = 16f
            isChecked = LoginConfig.isVoiceReadEnabled(ctx)

            setOnCheckedChangeListener { _, checked ->
                LoginConfig.setVoiceReadEnabled(ctx, checked)
                // 👇 联动控制显示/隐藏
                cbVoiceReadTwo.visibility = if (checked) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        }

        cbVoiceReadTwo = CheckBox(ctx).apply {
            text = "每条验证码播报两次"
            textSize = 16f
            isChecked = LoginConfig.isVoiceReadEnabledTwo(ctx)

            setOnCheckedChangeListener { _, checked ->
                LoginConfig.setVoiceReadEnabledTwo(ctx, checked)
            }
        }
        cbVoiceReadTwo.visibility =
            if (LoginConfig.isVoiceReadEnabled(ctx)) View.VISIBLE else View.GONE
        val schemeTitle = TextView(ctx).apply {
            text = "自动填充方案选择"
            textSize = 16f
        }

        radioGroup = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
        }

        rbScheme1 = RadioButton(ctx).apply {
            text = "方案1"
            textSize = 16f
            id = View.generateViewId()
        }

        rbScheme2 = RadioButton(ctx).apply {
            text = "方案2"
            textSize = 16f
            id = View.generateViewId()
        }

        radioGroup.addView(rbScheme1)
        radioGroup.addView(rbScheme2)

        when (LoginConfig.getScheme(ctx)) {
            1 -> rbScheme1.isChecked = true
            2 -> rbScheme2.isChecked = true
            else -> rbScheme1.isChecked = true
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->

            val scheme =
                if (checkedId == rbScheme1.id) {
                    1
                } else {
                    2
                }

            LoginConfig.setScheme(
                ctx,
                scheme
            )
        }

        settingsContainer.addView(cbAutoFill)
        settingsContainer.addView(cbAutoShowCodeFloat)
        settingsContainer.addView(cbVoiceRead)
        settingsContainer.addView(cbVoiceReadTwo)
        settingsContainer.addView(schemeTitle)
        settingsContainer.addView(radioGroup)

        container.addView(settingsContainer)
        // ================= 功能设置 =================
        val tvTest = TextView(ctx).apply {
            text = "验证码功能测试"
            gravity = Gravity.CENTER
            textSize = 16f
            //setTypeface(typeface, Typeface.BOLD)

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 12)
                bottomMargin = dp(ctx, 12)
            }
        }
        container.addView(tvTest)
        // 手机号
        val phoneLayout = TextInputLayout(ctx).apply {
            hint = context.getString(R.string.phonexpq)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        etPhone = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            filters = arrayOf(InputFilter.LengthFilter(11))
            setText("13800138000")
        }
        phoneLayout.addView(etPhone)
        container.addView(phoneLayout, lpMatch(ctx))

        // ================= 图形验证码行 =================

        val imgCodeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val imgCodeLayout = TextInputLayout(ctx).apply {
            hint = context.getString(R.string.tuxingxpq)
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
            hint = context.getString(R.string.smsxpq)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        etCode = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        codeLayout.addView(etCode)


        btnSendCode = TextView(ctx).apply {
            text = context.getString(R.string.getsmsxpq)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            setTextColor(
                getPrimaryColorByTheme(ctx)
            )
        }

        codeRow.addView(
            codeLayout,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        codeRow.addView(btnSendCode)

        container.addView(codeRow, lpMatch(ctx))

        // 帮助按钮（文字 + ? icon）
        helpContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }

        btnHelp = TextView(ctx).apply {
            text = context.getString(R.string.helptzxpq)
            textSize = 16f
            setTextColor(
                getPrimaryColorByTheme(ctx)
            )
        }

        val primaryColor = getPrimaryColorByTheme(ctx)

        val ivHelpIcon = AppCompatImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_help)
            imageTintList = ColorStateList.valueOf(primaryColor)
            val size = dp(ctx, 18)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = dp(ctx, 4)
            }
        }



        helpContainer.addView(btnHelp)
        helpContainer.addView(ivHelpIcon)

        container.addView(
            helpContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 8)
            }
        )

        // 登录按钮
        val loginButtonView = createLoginButton(ctx)
        btnLogin = loginButtonView as Button
        container.addView(loginButtonView)

        // 协议
        cbAgree = CheckBox(ctx).apply {
            text = context.getString(R.string.agreexieyixpq)
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
        helpContainer.setOnClickListener {
            // 使用 FragmentManager 来显示 BottomSheetDialogFragment
            (context as? AppCompatActivity)?.let {
                val sheet = SensitiveNotificationBottomSheet()
                sheet.show(context.supportFragmentManager, SensitiveNotificationBottomSheet.TAG)
            }

        }




        btnSendCode.setOnClickListener {
            val phone = etPhone.text?.toString().orEmpty()
            if (!isPhoneValid(phone)) {
                toast(msg = context.getString(R.string.okphonexpq))
                return@setOnClickListener
            }
            fun sendCode() {
                //发送验证码接口
                val code = generateCode(6)
                sendNotification(code = code)
                // 可选：存起来用于校验
                mockSmsCode = code
                startCountDown()
            }

            fun sendCodeByNotification() {
                //发送通知权限
                if (!NotificationUtilXpq.isNotificationEnabled()){
                    AlertDialog.Builder(context)
                        .setMessage(context.getString(R.string.sendcodexpq))
                        .setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                            if (context is Activity){
                                val easyPermission = AliveUtils.easyRequestPermission(context, PermissionLists.getPostNotificationsPermission(),"发送通知")
                                if (easyPermission) {
                                    sendCode()
                                }
                            }
                        }
                        .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->

                        }
                        .setNeutralButton(context.getString(R.string.sxzxpq)){_, _ ->
                            shouxianzhi()
                        }
                        .show()


                }else{
                    sendCode()
                }
            }



            if (hasAccessibility){
                //有,无障碍
                if (!NotificationUtilXpq.isAccessibilityEnabled()){
                    //无障碍为空
                    AlertDialog.Builder(context)
                        .setMessage(context.getString(R.string.duqucodewuzhangaixpq))
                        .setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                            NotificationUtilXpq.gotoAccessibilitySetting()
                        }
                        .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->

                        }
                        .setNeutralButton(context.getString(R.string.sxzxpq)){_, _ ->
                            shouxianzhi()
                        }
                        .show()
                }else{
                    //无障碍不为空
                    sendCodeByNotification()
                }

            }else{
                //没有无障碍
                //读取通知权限
                if (!NotificationUtilXpq.isNotificationListenersEnabled()) {
                    //没有读取通知权限
                    AlertDialog.Builder(context)
                        .setMessage(context.getString(R.string.duqucodexpq))
                        .setPositiveButton(context.getString(R.string.ok)) { _, _ ->
                            NotificationUtilXpq.gotoNotificationAccessSetting()
                        }
                        .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->

                        }
                        .setNeutralButton(context.getString(R.string.sxzxpq)){_, _ ->
                            shouxianzhi()
                        }
                        .show()
                }else{
                    //有读取通知权限
                    sendCodeByNotification()
                }

            }


        }

        btnLogin.setOnClickListener {
            val phone = etPhone.text?.toString().orEmpty()
            val code = etCode.text?.toString().orEmpty()
            val imgCode = etImgCode.text?.toString().orEmpty()
            if (!cbAgree.isChecked) {
                toast(msg = context.getString(R.string.xiantyxpq))
                return@setOnClickListener
            }
            if (!isPhoneValid(phone)) {
                toast(msg = context.getString(R.string.phoneerrorxpq))
                return@setOnClickListener
            }

            if (imgCode != mockImgCode) {
                toast(msg = context.getString(R.string.tuxingerrorxpq))
                refreshImgCode()
                return@setOnClickListener
            }

            if (code != mockSmsCode) {
                toast(msg = context.getString(R.string.smserrorxpq))
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
                btnSendCode.text = context.getString(R.string.getsmsxpq)
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


}
