package com.google.android.accessibility.inputmethod

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.android.accessibility.ext.databinding.ActivityAliveXpqBinding
import com.google.android.accessibility.ext.activity.XpqBaseActivity
import com.google.android.accessibility.inputmethod.KeepAliveInputMethod.Companion.XPQ_IME_FLOATING
import com.google.android.accessibility.inputmethod.KeepAliveInputMethod.Companion.XPQ_IME_SETTINGS

class ImeSettingsActivity : XpqBaseActivity<ActivityAliveXpqBinding>(
    bindingInflater = ActivityAliveXpqBinding::inflate
) {

    private lateinit var switchFloatingWindow: SwitchCompat
    private val prefs by lazy { getSharedPreferences(XPQ_IME_SETTINGS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ScrollView 根布局
        val scrollView = ScrollView(this)

        // LinearLayout 包裹内容
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(container)
        setContentView(scrollView)

        // 添加透明占位 View，作为顶部 20dp 间距
        val topSpace = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    50f,
                    resources.displayMetrics
                ).toInt()
            )
        }
        container.addView(topSpace)

        // 创建 SwitchCompat
        switchFloatingWindow = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean(XPQ_IME_FLOATING, false)
            text = "切换到其它输入法时\n显示悬浮窗,方便切回"
            // 设置左右 padding 32dp
            val sidePaddingPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                32f,
                resources.displayMetrics
            ).toInt()
            setPadding(
                sidePaddingPx, // left
                paddingTop,    // 保留原 top padding
                sidePaddingPx, // right
                paddingBottom  // 保留原 bottom padding
            )
        }

        // 添加到 LinearLayout
        container.addView(switchFloatingWindow)

        // 开关监听
        switchFloatingWindow.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(XPQ_IME_FLOATING, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "悬浮窗已启用" else "悬浮窗已禁用",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun initView_Xpq() { }

    override fun initData_Xpq() { }
}