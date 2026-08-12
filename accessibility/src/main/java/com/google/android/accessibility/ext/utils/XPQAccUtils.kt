package com.google.android.accessibility.ext.utils

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * Company    : 
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/6/27  3:07
 * Description:This is XPQAccUtils
 */
object XPQAccUtils {

    /**
     * 开启指定无障碍服务（保留已开启的其它服务）
     *
     * @param context Context
     * @param services 服务名，格式：packageName/className
     *
     *  "${context.packageName}/${AccessibilityServiceImp::class.java.name}",
     *     "${context.packageName}/${SelectToSpeakService::class.java.name}"
     */
    @JvmOverloads
    @JvmStatic
    fun enableAccessibilityServices(
        context: Context = appContext ,
        vararg services: String
    ): Boolean {

        if (services.isEmpty()) {
            return false
        }

        val resolver = context.contentResolver

        val enabledServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
            ?.split(":")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toMutableSet()
            ?: mutableSetOf()

        enabledServices.addAll(services)

        val success1 = Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            enabledServices.joinToString(":")
        )

        val success2 = Settings.Secure.putInt(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            1
        )

        return success1 && success2
    }

    /**
     * 关闭指定无障碍服务（保留其它服务）
     *
     * @param context Context
     * @param services 服务名，格式：packageName/className
     */
    @JvmOverloads
    @JvmStatic
    fun disableAccessibilityServices(
        context: Context = appContext,
        vararg services: String
    ): Boolean {

        if (services.isEmpty()) {
            return false
        }

        val resolver = context.contentResolver

        val enabledServices = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
            ?.split(":")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toMutableSet()
            ?: return true

        enabledServices.removeAll(services.toSet())

        val success = Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            enabledServices.joinToString(":")
        )

        if (enabledServices.isEmpty()) {
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
        }

        return success
    }

    @JvmStatic
    @JvmOverloads
    fun show_AC_Warn_Dialog(
        context: Context,
        forceShow:  Boolean = false
    ) {
        val preferences = context.getSharedPreferences(
            "ac_warn_dialog",
            Context.MODE_PRIVATE
        )

        val dontShowAgain = preferences.getBoolean(
            "dont_show_again",
            false
        )

        // 已选择“不再显示”，且不是强制显示
        if (dontShowAgain && !forceShow) {
            return
        }
        // =========================
        // 根布局
        // =========================
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // =========================
        // 标题
        // =========================
        val tvTitle = TextView(context).apply {
            text = "注意事项"
            textSize = 20f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        dialogView.addView(
            tvTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================
        // 提示文字
        // =========================
        val tvTMSG = TextView(context).apply {
            val text = "随着AI的发展,编程门槛降低,几乎人人都能手搓一个app了,所以自动化软件也越来越多了,导致平台加强了对自动化的检测,因此:" +
                    "\n在使用自动化软件时候,可能会出现安全提示让你做题," +
                    "大概率不会被封,但为了稳妥起见(以防万一),可以多备几个小号,俗话说,手中有粮心中不慌" +
                    "\nPS:\n1.我自己也在用,而且用了好几年了,一直很稳定(本软件诞生的初衷就是给我自己用的), \n2.目前为止也没有人反馈说被封了的"

            val spannable = SpannableString(text)

            // 红色 + 加粗
            fun highlight(target: String) {
                val start = text.indexOf(target)
                if (start >= 0) {
                    val end = start + target.length

                    spannable.setSpan(
                        ForegroundColorSpan(Color.RED),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            highlight("可能会出现安全提示让你做题")
            highlight("稳妥起见")
            highlight("可以多备几个小号")
            highlight("手中有粮心中不慌")
            highlight("本软件诞生的初衷就是给我自己用的")

            this.text = spannable
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 24)
        }

        dialogView.addView(
            tvTMSG,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        // =========================
        // 对比文字
        // =========================
        val tvBI = TextView(context).apply {
            text = "自动化技术原理对比"
            textSize = 20f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.START
            setPadding(0, 0, 0, 24)
        }

        dialogView.addView(
            tvBI,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        // =========================
        // 表格
        // =========================
        val tableLayout = TableLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isStretchAllColumns = true
        }

        // =========================
        // 表头
        // =========================
        val headers = listOf(
            "自动化技术",
            "权限级别",
            "安全性"
        )

        val headerRow = TableRow(context).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }

        for (header in headers) {

            val tv = TextView(context).apply {
                text = header
                setPadding(16, 16, 16, 16)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK)
                textSize = 14f
            }

            val params = TableRow.LayoutParams(
                0,
                TableRow.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }

            headerRow.addView(tv, params)
        }

        tableLayout.addView(headerRow)

        // =========================
        // 表格数据
        // =========================
        val data = listOf(
            listOf("无障碍", "中", "高"),
            listOf("ADB", "高", "低"),
            listOf("Shizuku", "高", "低"),
            listOf("Root", "极高", "极低")
        )

        for ((rowIndex, rowData) in data.withIndex()) {

            val tableRow = TableRow(context)

            // 斑马线
            val bgColor =
                if (rowIndex % 2 == 0) {
                    0xFFF9F9F9.toInt()
                } else {
                    Color.WHITE
                }

            tableRow.setBackgroundColor(bgColor)

            for ((colIndex, cell) in rowData.withIndex()) {

                val tv = TextView(context).apply {
                    text = cell
                    setPadding(16, 16, 16, 16)
                    gravity = Gravity.CENTER
                    setSingleLine(false)
                    ellipsize = null
                    textSize = 14f

                    // 第一列加粗
                    if (colIndex == 0) {
                        setTypeface(null, Typeface.BOLD)
                    }

                    // 安全性高亮
                    if (colIndex > 1) {
                        setTextColor(Color.parseColor("#FF5722"))
                        setBackgroundColor(0xFFFFF3E0.toInt())
                    } else {
                        setTextColor(Color.BLACK)
                    }
                }

                val params = TableRow.LayoutParams(
                    0,
                    TableRow.LayoutParams.WRAP_CONTENT
                ).apply {
                    weight = 1f
                }

                tableRow.addView(tv, params)
            }

            tableLayout.addView(tableRow)
        }

        dialogView.addView(tableLayout)

        // =========================
        // 总结
        // =========================
        val tvEnd = TextView(context).apply {
            val text = "总结: 安卓自动化技术就这么几类,既然都能被平台检测出来,那为何不选一个相对最安全的无障碍呢?鱼与熊掌不可兼得,我们在享受自动化便利的同时,不可避免的也要承担一定的风险," +
                    "如果你从小就是一个听话守规矩的老实人,那还是老老实实的自己手动操作吧"

            val spannable = SpannableString(text)

            // 红色 + 加粗
            fun highlight(target: String) {
                val start = text.indexOf(target)
                if (start >= 0) {
                    val end = start + target.length

                    spannable.setSpan(
                        ForegroundColorSpan(Color.RED),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            highlight("鱼与熊掌不可兼得")
            highlight("我们在享受自动化便利的同时,不可避免的也要承担一定的风险")

            this.text = spannable
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 30, 0, 24)
        }

        dialogView.addView(
            tvEnd,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================
        // 显示 Dialog
        // =========================
        val builder = AlertDialog.Builder(context)
            .setView(dialogView)
            .setPositiveButton("我知道了") { dialog, _ ->
                dialog.dismiss()
            }

        // 只有非强制显示时，才提供“不再显示”
        if (!forceShow) {
            builder.setNegativeButton("不再显示") { dialog, _ ->
                preferences.edit()
                    .putBoolean("dont_show_again", true)
                    .apply()

                dialog.dismiss()
            }
        }

        builder.show()

        //return dialogView
    }

}