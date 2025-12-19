package com.google.android.accessibility.ext.utils

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object NumberPickerDialog {
    @JvmOverloads
    @JvmStatic
    fun show(
        context: Context,
        title: String,
        min: Int,
        max: Int,
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

        /** ---------------- Dialog ---------------- */
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("确定") { _, _ ->
                val value = picker.value
                val index = value - min
                val text = displayedValues?.getOrNull(index) ?: value.toString()
                KeyguardUnLock.setUnLockMethod(value)
                onConfirm(value, text)
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()

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
