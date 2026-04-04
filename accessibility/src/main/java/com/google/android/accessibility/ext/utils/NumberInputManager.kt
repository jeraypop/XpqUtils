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
        onNumberSaved: ((Int) -> Unit)? = null
    ) {
        if (context !is android.app.Activity) {
            //throw IllegalArgumentException("Context 必须是 Activity")
            AliveUtils.toast(msg = "Context 必须是 Activity")
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editText = EditText(context).apply {
            hint = "输入数字"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            val value = prefs.getInt(KEY_NUMBER, DEFAULT_VALUE)
            setText(value.toString())
            setSelection(text.length) // 光标移到末尾
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("保存") { dialog, _ ->
                val number = editText.text.toString().toIntOrNull() ?: DEFAULT_VALUE
                prefs.edit().putInt(KEY_NUMBER, number).apply()
                onNumberSaved?.invoke(number)
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
}