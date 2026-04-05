package com.google.android.accessibility.ext.utils



import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext


/**
 * NumberInputSDK：单例对象，实现数字输入弹窗和持久化
 */
object NumberInputSDK {

    private const val PREFS_NAME = "NumberInputSDKPrefs"
    private const val KEY_NUMBER = "saved_number"
    private const val DEFAULT_VALUE = 6

    /**
     * 弹出输入数字对话框
     * @param context Activity 或 Fragment 的 context
     * @param title 弹窗标题
     * @param onNumberSaved 回调：保存后的数字
     */
    @JvmStatic
    @JvmOverloads
    fun showNumberInputDialog(
        context: Context,
        title: String = "定时时间和执行时间误差范围",
        message: String = "当设备进入深度休眠模式的时候(特别是夜间),可能会导致第三方软件的定时时间有延时,所以:" +
                "\n1.要尽可能的避免设备进入休眠(做不到完全避免,胳膊拧不过大腿)," +
                "\n2.根据自己的设备,适当设置延时容差.大多数设备 6分钟的延时即可,具体根据软件运行日志提示来调整" +
                "\n例如:\n  你设置的容差是 6分钟,定时器时间是22:00,如果系统休眠,延时触发了定时器,软件经过比对当前时间和设置的时间后.22:06之前还会执行,22:06之后会放弃这次任务." +

                "\n注意:\n  部分设备的系统有bug,在切换夜间模式和白天模式时,会误触发定时器的执行,所以误差时间不建议过大,否则软件过滤不掉,会导致意外执行" +
                ".",
        onNumberSaved: ((Int) -> Unit)? = null
    ) {
        if (context !is android.app.Activity) {
            AliveUtils.toast(msg = "Context 必须是 Activity")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val editText = EditText(context).apply {
            hint = "输入数字"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true

            val value = prefs.getInt(KEY_NUMBER, DEFAULT_VALUE)
            setText(value.toString())
            setSelection(text.length)
        }

        val textView = android.widget.TextView(context).apply {
            text = message
            textSize = 14f
            setLineSpacing(4f, 1.1f)
            setPadding(0.dp(context), 0.dp(context), 0.dp(context), 12.dp(context))
        }

        val inputLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL

            addView(editText, android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val unitView = android.widget.TextView(context).apply {
                text = ":分钟"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                setPadding(8.dp(context), 0, 0, 0)
            }

            addView(unitView)
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16.dp(context), 12.dp(context), 16.dp(context), 4.dp(context))
            addView(textView)
            addView(inputLayout)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = editText.text.toString()
            val number = text.toIntOrNull()

            val min = 0
            val max = 24*60

            if (number == null || number !in min..max) {
                editText.error = "请输入 $min ~ $max 之间的数字,6 左右即可"
                return@setOnClickListener
            }

            prefs.edit().putInt(KEY_NUMBER, number).apply()
            onNumberSaved?.invoke(number)
            dialog.dismiss()
            AliveUtils.toast(msg = ""+getSavedNumber()+" 保存成功,建议6左右即可")
        }
    }

    /**
     * 获取上次保存的数字
     */
    @JvmStatic
    @JvmOverloads
    fun getSavedNumber(context: Context = appContext): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_NUMBER, DEFAULT_VALUE)
    }

    /**
     * 清理保存的数字
     */
    @JvmStatic
    @JvmOverloads
    fun clearNumber(context: Context = appContext) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_NUMBER).apply()
    }

    fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()
}