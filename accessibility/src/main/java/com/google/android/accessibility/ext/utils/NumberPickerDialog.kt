package com.google.android.accessibility.ext.utils

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.android.accessibility.ext.utils.AliveUtils.keepAliveByFloatingWindow
import com.google.android.accessibility.ext.utils.KeyguardUnLock.wakeKeyguardOn
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout


object NumberPickerDialog {
    @JvmStatic
    fun showDefault(
        context: Context
    ) {
        show(
            context = context,
            title = "解锁方案",
            min = 0,
            max = 3,
            displayedValues = arrayOf("关闭","方案1", "方案2", "方案3"),
            explainTexts = arrayOf(
                "关闭: 不会自动点亮屏幕和解锁",
                "方案1: 会尝试直接取消锁屏,当设备未设置密码锁屏时(仅划动解锁),即点亮屏幕后,可能就会直接进入系统了",
                "方案2: 会模拟屏幕上划,即点亮屏幕后,上划一下屏幕后进入系统或者呼出输入解锁密码的界面",
                "方案3: 跟方案2效果上类似,但兼容性更强,大多数新设备都能成功(注:解锁成功后,可能会额外打开本软件一下)"
            ),
            descText = "请选择一种最适合本设备的解锁方案",
            onValueChange = { _, text ->
                //previewTextView.text = "当前等级：$text"
            }
        ) { value, text ->
            AliveUtils.toast(msg =  "value=$value text=$text")
        }
    }

    @JvmOverloads
    @JvmStatic
    fun show(
        context: Context,
        title: String,
        min: Int = 0,
        max: Int = 3,
        defaultValue: Int = min,
        displayedValues: Array<String>? = null,
        explainTexts: Array<String>? = null,   // ★ 新增：方案解释
        descText: String? = null,               // 固定说明
        onValueChange: ((value: Int, text: String) -> Unit)? = null,
        onConfirm: (value: Int, text: String) -> Unit
    ) {

        /** ---------------- ScrollView（防裁剪） ---------------- */
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
        }

        /** ---------------- 内容容器 ---------------- */
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 8))
        }

        scrollView.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        /** ---------------- 固定说明 ---------------- */
        val descTextView = TextView(context).apply {
            text = descText.orEmpty()
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 4))
        }

        /** ---------------- 方案解释（动态） ---------------- */
        val explainTextView = TextView(context).apply {
            textSize = 13f
            setTypeface(null, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 6))
        }

        /** ---------------- 当前值（动态 + 强调） ---------------- */
        val valueTextView = TextView(context).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 12))
        }
        /** ---------------- Switch（即时生效） ---------------- */

        val LP_Switch = SwitchCompat(context).apply {
            text = "亮屏后自动解除锁屏\n(仅适用于无密码锁屏)"
            textSize = 14f
            showText = true
            textOn = "开"
            textOff = "关"
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            //某些设备在 isChecked = xxx 时可能触发监听 初始化状态（防止误触发）
            setOnCheckedChangeListener(null)
            // 读取已保存状态
            isChecked = KeyguardUnLock.getAutoDisableKeyguard()

            // ★ 关键：切换即保存
            setOnCheckedChangeListener { _, isChecked ->
                //禁用键盘锁
                wakeKeyguardOn()
                KeyguardUnLock.setAutoDisableKeyguard(isChecked)
            }
        }

        val enableSwitch = SwitchCompat(context).apply {
            text = "息屏后自动恢复锁屏\n(仅适用于无密码锁屏)"
            textSize = 14f
            showText = true
            textOn = "开"
            textOff = "关"
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            //某些设备在 isChecked = xxx 时可能触发监听 初始化状态（防止误触发）
            setOnCheckedChangeListener(null)
            // 读取已保存状态
            isChecked = KeyguardUnLock.getAutoReenKeyguard()

            // ★ 关键：切换即保存
            setOnCheckedChangeListener { _, isChecked ->
                //禁用键盘锁 为啥不用 wakeKeyguardOff 因为可能会直接锁屏
                wakeKeyguardOn()
                KeyguardUnLock.setAutoReenKeyguard(isChecked)
            }
        }

        val screenOnSwitch = SwitchCompat(context).apply {
            text = "保持屏幕常亮不熄灭\n(无法解锁时可开启)"
            textSize = 14f
            showText = true
            textOn = "开"
            textOff = "关"
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            //某些设备在 isChecked = xxx 时可能触发监听 初始化状态（防止误触发）
            setOnCheckedChangeListener(null)
            // 读取已保存状态
            isChecked = KeyguardUnLock.getScreenAlwaysOn()

            // ★ 关键：切换即保存
            setOnCheckedChangeListener { _, isChecked ->
                KeyguardUnLock.setScreenAlwaysOn(isChecked)
                val intent = Intent("shuaxin_mima")
                context.sendBroadcast(intent)
                //先关闭
                keepAliveByFloatingWindow(context, false, null)
                //延时3秒再开启
                //enable = true 不变,但内部的常亮开关状态会改变
                Handler(Looper.getMainLooper()).postDelayed(Runnable {
                    keepAliveByFloatingWindow(context, true, null)
                }, 3000)
            }
        }
        /** ---------------- 数字密码输入 ---------------- */
        val passwordLayout = TextInputLayout(context).apply {
            hint = "请点此输入数字解锁密码"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }


        val passwordEditText = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
        }
        // 回填
        passwordEditText.setText(
            KeyguardUnLock.getScreenPassWord()
        )
        passwordEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val pwd = s?.toString().orEmpty()
                KeyguardUnLock.setScreenPassWord(pwd)
                val intent = Intent("shuaxin_mima")
                context.sendBroadcast(intent)

            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })


        passwordLayout.addView(
            passwordEditText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )



        /** ---------------- NumberPicker ---------------- */
        val picker = NumberPicker(context)

        picker.displayedValues = null
        picker.minValue = min
        picker.maxValue = max

        if (displayedValues != null) {
            require(displayedValues.size == max - min + 1)
            picker.displayedValues = displayedValues
        }

        if (explainTexts != null) {
            require(explainTexts.size == max - min + 1) {
                "explainTexts.size 必须等于 max - min + 1"
            }
        }

        picker.wrapSelectorWheel = true

        val lastValue = KeyguardUnLock.getUnLockMethod(defaultValue)
        picker.value = lastValue.coerceIn(min, max)

        /** ---------------- 文案刷新方法 ---------------- */
        fun updateTexts(value: Int) {
            val index = value - min
            val titleText = displayedValues?.getOrNull(index) ?: value.toString()
            val explain = explainTexts?.getOrNull(index).orEmpty()

            explainTextView.text = explain
            valueTextView.text = "当前选择：$titleText"
            // ★ 核心：只在方案1显示 Switch
            enableSwitch.visibility =
                if (value == 1) View.VISIBLE else View.GONE
            //切走方案1时自动关闭 Switch（并保存）：
//            if (value != 1 && enableSwitch.isChecked) {
//                enableSwitch.isChecked = false
//                KeyguardUnLock.setAutoReenKeyguard(false)
//            }

            LP_Switch.visibility =
                if (value == 1) View.VISIBLE else View.GONE
            passwordLayout.visibility =
                if (value == 0) View.GONE else View.VISIBLE

        }

        updateTexts(picker.value)

        picker.setOnValueChangedListener { _, _, newVal ->
            updateTexts(newVal)
            val index = newVal - min
            val text = displayedValues?.getOrNull(index) ?: newVal.toString()
            onValueChange?.invoke(newVal, text)
        }

        /** ---------------- 组装 ---------------- */
        if (!descText.isNullOrEmpty()) {
            container.addView(descTextView)
        }
        container.addView(explainTextView)
        container.addView(valueTextView)
        container.addView(picker)
        container.addView(passwordLayout)

        container.addView(LP_Switch)
        container.addView(enableSwitch)
        container.addView(screenOnSwitch)



        /** ---------------- Dialog ---------------- */
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("确定",null)
            .setNegativeButton("取消", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newValue = picker.value
            val oldValue = KeyguardUnLock.getUnLockMethod(defaultValue)

            val index = newValue - min
            val text = displayedValues?.getOrNull(index) ?: newValue.toString()

            // ★ 只有 1 -> 0/2 / 3 才提示
            if (oldValue == 1 && newValue != 1) {
              /*  if (KeyguardUnLock.keyguardIsOn()){
                    AliveUtils.toast(msg = "键盘已解锁！")
                    if (mKeyguardManager == null) {
                        mKeyguardManager = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    }
                    if (mKeyguardManager?.isKeyguardLocked== true){
                        AliveUtils.toast(msg = "第二次判断 键盘没解锁！")
                    }else{
                        AliveUtils.toast(msg = "第二次判断 键盘已解锁！")
                    }
                }else{
                    AliveUtils.toast(msg = "键盘没解锁！")
                }*/
                val tit = if (newValue == 0){"关闭解锁方案"}else{"切换解锁方案"}
                val msg = if (newValue == 0){"从方案1关闭"}else{"从方案1切换到其它方案"}
                AlertDialog.Builder(context)
                    .setTitle(tit)
                    .setMessage(
                        msg+ "，\n在部分设备上可能会出现锁屏一次或需要手动唤醒的情况。\n\n" +
                                "确定要继续吗？"
                    )
                    .setPositiveButton("继续") { _, _ ->
                        KeyguardUnLock.setUnLockMethod(newValue)
                        onConfirm(newValue, text)
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                // 其它情况，直接切换
                KeyguardUnLock.setUnLockMethod(newValue)
                onConfirm(newValue, text)
                dialog.dismiss()
            }
        }


        /** ---------------- 最大高度限制 ---------------- */
        dialog.window?.let { window ->
            val dm = context.resources.displayMetrics
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.65f).toInt()
            )
        }
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
