package com.google.android.accessibility.ext.utils



import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import kotlin.random.Random


/**
 * NumberInputSDK：单例对象，实现数字输入弹窗和持久化
 */
object NumberInputSDK {

    private const val PREFS_NAME = "NumberInputSDKPrefs"
    private const val KEY_NUMBER = "saved_number"
    private const val DEFAULT_VALUE = 6

    private const val KEY_SECONDS = "saved_seconds"
    private const val DEFAULT_SECONDS = 3
    private const val MIN_SECONDS = 0
    private const val MAX_SECONDS = 600

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
                "\n1.要尽可能的避免设备进入休眠(做不到完全避免,胳膊拧不过大腿，除非屏幕一直常亮)," +
                "\n2.根据自己的设备,适当设置延时容差.大多数设备 6分钟的延时即可,具体根据软件运行日志提示来调整" +
                "\n例如:\n  你设置的容差是 6分钟,定时器时间是22:00,如果系统休眠,延时触发了定时器,软件经过比对当前时间和设置的时间后.22:06之前还会执行,22:06之后会放弃这次任务." +

                "\n注意:\n  部分设备的系统有bug,在切换夜间模式和白天模式时,会误触发定时器的执行,所以误差时间不建议过大, 6 左右即可,否则软件过滤不掉,会导致意外执行" +
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
     * 弹出输入秒数对话框，保存输入的秒数
     * 结构与 [showNumberInputDialog] 一致，单位改为「秒」，单独 key 持久化
     * @param context Activity 或 Fragment 的 context
     * @param title 弹窗标题
     * @param onSecondsSaved 回调：保存后的秒数
     */
    @JvmStatic
    @JvmOverloads
    fun showSecondsInputDialog(
        context: Context,
        title: String = "随机延时时间(秒)",
        message: String = "在每次执行操作前，随机延时几秒的时间:" +
                "\n例如:\n  你设置的是 6秒,执行每一个步骤时，都会随机停顿0到6秒的任意一个时间." +
                "\n\n注意:\n  时间不建议过大,否则会很慢，也不建议过小，否则就没有任何意义.",
        onSecondsSaved: ((Int) -> Unit)? = null
    ) {
        if (context !is android.app.Activity) {
            AliveUtils.toast(msg = "Context 必须是 Activity")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val editText = EditText(context).apply {
            hint = "输入秒数"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true

            val value = prefs.getInt(KEY_SECONDS, DEFAULT_SECONDS)
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
                text = ":秒"
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

            val min = MIN_SECONDS
            val max = MAX_SECONDS

            if (number == null || number !in min..max) {
                editText.error = "请输入 $min ~ $max 之间的秒数,3 左右即可"
                return@setOnClickListener
            }

            prefs.edit().putInt(KEY_SECONDS, number).apply()
            onSecondsSaved?.invoke(number)
            dialog.dismiss()
            AliveUtils.toast(msg = "" + getSavedSeconds() + " 秒 保存成功,建议3左右即可")
        }
    }

    /**
     * 获取上次保存的秒数
     */
    @JvmStatic
    @JvmOverloads
    fun getSavedSeconds(context: Context = appContext): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SECONDS, DEFAULT_SECONDS)
    }

    // ---- 洗牌袋（shuffle bag）状态：0 ~ 已保存秒数 之间的整数逐个不重复取出 ----
    @Volatile
    private var bagSeconds: List<Int>? = null
    @Volatile
    private var bagIndex = 0
    @Volatile
    private var bagForValue = -2   // 与合法 upper(>=0) 不同，强制首次构建
    @Volatile
    private var bagLast = -1

    /**
     * 真随机取一个秒数：在 0 ~ 已保存秒数(含) 范围内洗牌逐个取，
     * 每次都不重复，直到把整个范围循环一遍后重新洗牌。
     * 若已保存秒数发生变化，会自动重建池。
     */
    @JvmStatic
    @JvmOverloads
    fun getRandomSeconds(context: Context = appContext): Int = synchronized(this) {
        val upper = getSavedSeconds(context)
        val bag = bagSeconds
        if (bag == null || bagIndex >= bag.size || bagForValue != upper) {
            val fresh = (0..upper).toMutableList()
            fresh.shuffle(Random)
            // 避免新一轮首值与上轮末值相同，保证相邻两次也“不一样”
            if (fresh.size > 1 && fresh.first() == bagLast) {
                val tmp = fresh[1]
                fresh[1] = fresh[0]
                fresh[0] = tmp
            }
            bagSeconds = fresh
            bagIndex = 0
            bagForValue = upper
        }
        val list = bagSeconds!!
        val v = list[bagIndex]
        bagIndex++
        bagLast = v
        v
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