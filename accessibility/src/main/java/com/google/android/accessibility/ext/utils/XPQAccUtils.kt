package com.google.android.accessibility.ext.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
        forceShow: Boolean = false
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

        // =========================================================
        // ScrollView
        // =========================================================
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        // =========================================================
        // 根布局
        // =========================================================
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                24,
                24,
                24,
                24
            )

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(dialogView)

        // =========================================================
        // 标题
        // =========================================================
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

        // =========================================================
        // 提示文字
        // =========================================================
        val tvTMSG = TextView(context).apply {

            val text =
                "无障碍辅助功能不是洪水猛兽，因为很多软件都使用了(如：抖音，小红书，腾讯手机管家等等)，点此查看详情。" +
                        "\n当然也不排除被有些恶意软件使用，就跟菜刀一样，可以用来切菜，也可以用来干坏事。牢记准则：不要随便授权来历不明的软件" +
                        "\n随着AI的发展,编程门槛降低,几乎人人都能手搓一个app了,所以自动化软件也越来越多了,导致平台加强了对自动化的检测,因此:" +
                        "\n在使用自动化软件的时候,可能会出现安全提示让你做题," +
                        "大概率不会被封,但为了稳妥起见(以防万一),可以多备几个小号(缺点：小号更容易触发风控，优点：就算最差的情况出现，重新注册一个就是),俗话说,手中有粮心中不慌" +
                        "\nPS:\n1.我自己也在用,而且用了好几年了,一直很稳定(本软件诞生的初衷就是给我自己用的), \n2.目前为止也没有人反馈说被封了的"

            val spannable = SpannableString(text)

            // =====================================================
            // 「点此查看详情」点击跳转
            // =====================================================
            val target = "点此查看详情"
            val start = text.indexOf(target)

            if (start >= 0) {

                val end = start + target.length

                spannable.setSpan(
                    object : ClickableSpan() {

                        override fun onClick(widget: View) {
                            try {

                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                        "https://mp.weixin.qq.com/s/ZKs_y2tcHyJMLJy3kwsiZg"
                                    )
                                )

                                context.startActivity(intent)

                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun updateDrawState(
                            ds: TextPaint
                        ) {
                            super.updateDrawState(ds)

                            ds.color = Color.BLUE
                            ds.isUnderlineText = true
                        }
                    },
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // =====================================================
            // 红色 + 加粗
            // =====================================================
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

            movementMethod = LinkMovementMethod.getInstance()

            highlightColor = Color.TRANSPARENT

            textSize = 14f

            setTextColor(Color.DKGRAY)

            setPadding(
                0,
                0,
                0,
                24
            )
        }

        dialogView.addView(
            tvTMSG,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================================================
        // 对比文字
        // =========================================================
        val tvBI = TextView(context).apply {

            text = "自动化技术原理对比"

            textSize = 20f

            setTextColor(Color.BLACK)

            setTypeface(null, Typeface.BOLD)

            gravity = Gravity.START

            setPadding(
                0,
                0,
                0,
                24
            )
        }

        dialogView.addView(
            tvBI,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================================================
        // 表格
        // =========================================================
        val tableLayout = TableLayout(context).apply {

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            isStretchAllColumns = true
        }

        // =========================================================
        // 表头
        // =========================================================
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

                setPadding(
                    16,
                    16,
                    16,
                    16
                )

                gravity = Gravity.CENTER

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(Color.BLACK)

                textSize = 14f
            }

            val params = TableRow.LayoutParams(
                0,
                TableRow.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }

            headerRow.addView(
                tv,
                params
            )
        }

        tableLayout.addView(headerRow)

        // =========================================================
        // 表格数据
        // =========================================================
        val data = listOf(
            listOf("无障碍", "中", "中"),
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

                    setPadding(
                        16,
                        16,
                        16,
                        16
                    )

                    gravity = Gravity.CENTER

                    setSingleLine(false)

                    ellipsize = null

                    textSize = 14f

                    // 第一列加粗
                    if (colIndex == 0) {
                        setTypeface(
                            null,
                            Typeface.BOLD
                        )
                    }

                    // 安全性高亮
                    if (colIndex > 1) {

                        setTextColor(
                            Color.parseColor("#FF5722")
                        )

                        setBackgroundColor(
                            0xFFFFF3E0.toInt()
                        )

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

                tableRow.addView(
                    tv,
                    params
                )
            }

            tableLayout.addView(tableRow)
        }

        dialogView.addView(tableLayout)

        // =========================================================
        // 总结
        // =========================================================
        val tvEnd = TextView(context).apply {

            val text =
                "总结: 安卓自动化技术就这么几类,既然都能被平台检测出来,那为何不选一个相对最安全的无障碍呢?鱼与熊掌不可兼得,我们在享受自动化便利的同时,不可避免的也要承担一定的风险," +
                        "如果你从小就是一个听话守规矩的老实人,那还是老老实实的自己手动操作吧"

            val spannable = SpannableString(text)

            // =====================================================
            // 红色 + 加粗
            // =====================================================
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

            highlight(
                "我们在享受自动化便利的同时,不可避免的也要承担一定的风险"
            )

            this.text = spannable

            textSize = 16f

            setTextColor(Color.BLACK)

            setPadding(
                0,
                30,
                0,
                24
            )
        }

        dialogView.addView(
            tvEnd,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================================================
        // 风险确认输入框
        // forceShow = true 时以下功能全部不生效
        // =========================================================
        val confirmText =
            "我已充分了解可能的风险，毕竟鱼与熊掌不可兼得"

        // =========================================================
        // 以下全部只在非 forceShow 时添加
        // =========================================================
        var etConfirm: EditText? = null
        var tvConfirmStatus: TextView? = null

        if (!forceShow) {

            // =====================================================
            // 提示
            // =====================================================
            val tvConfirmTip = TextView(context).apply {

                text = "请完整输入以下文字："

                textSize = 14f

                setTextColor(Color.DKGRAY)

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    8,
                    0,
                    8
                )
            }

            dialogView.addView(
                tvConfirmTip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // =====================================================
            // 要求输入的文字
            // =====================================================
            val tvConfirmText = TextView(context).apply {

                text = confirmText

                textSize = 14f

                setTextColor(Color.RED)

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    4,
                    0,
                    12
                )
            }

            dialogView.addView(
                tvConfirmText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // =====================================================
            // 输入框
            // =====================================================
            val editText = EditText(context).apply {

                hint = "请输入上面的文字"

                textSize = 14f

                setSingleLine(true)

                setPadding(
                    16,
                    12,
                    16,
                    12
                )

                background = GradientDrawable().apply {

                    setColor(Color.WHITE)

                    setStroke(
                        1,
                        Color.GRAY
                    )

                    cornerRadius = 8f
                }
            }

            etConfirm = editText

            dialogView.addView(
                editText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 4
                }
            )

            // =====================================================
            // 输入状态提示
            // =====================================================
            val statusText = TextView(context).apply {

                textSize = 13f

                setPadding(
                    4,
                    4,
                    4,
                    12
                )

                visibility = View.GONE
            }

            tvConfirmStatus = statusText

            dialogView.addView(
                statusText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // =========================================================
        // ScrollView + 右侧提示
        // =========================================================
        val scrollContainer = FrameLayout(context)

        scrollContainer.addView(
            scrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================================================
        // 「滑动查看更多」提示
        // =========================================================
        val tvScrollHint = TextView(context).apply {

            text = "↕\n滑动到底关闭"

            textSize = 12f

            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER

            setPadding(
                8,
                10,
                8,
                10
            )

            background = GradientDrawable().apply {

                setColor(
                    0xCC555555.toInt()
                )

                cornerRadius = 20f
            }

            elevation = 8f

            visibility = View.GONE

            // 防止点击提示影响 ScrollView
            isClickable = false
            isFocusable = false
        }

        val hintParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {

            gravity =
                Gravity.END or Gravity.CENTER_VERTICAL

            rightMargin = 4
        }

        scrollContainer.addView(
            tvScrollHint,
            hintParams
        )

        // =========================================================
        // ScrollView 滚动监听
        // =========================================================
        scrollView.setOnScrollChangeListener {
                _,
                _,
                scrollY,
                _,
                _ ->

            val canScrollDown = scrollView.canScrollVertically(1)
            tvScrollHint.visibility =
                if (canScrollDown ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        // =========================================================
        // 创建 Dialog
        // =========================================================
        val builder = AlertDialog.Builder(context)
            .setView(scrollContainer)
            .setPositiveButton(
                "我知道了",
                null
            )

        // forceShow = true 时不显示“不再显示”
        if (!forceShow) {

            builder.setNegativeButton(
                "不再显示",
                null
            )
        }

        val dialog = builder.create()

        // =========================================================
        // 不允许点击空白关闭
        // =========================================================
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        // =========================================================
        // 键盘弹出时自动调整 Dialog
        // =========================================================
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        // =========================================================
        // Dialog 显示
        // =========================================================
        dialog.show()

        // =========================================================
        // Dialog 宽高
        //
        // 宽度：屏幕 90%
        // 高度：屏幕 50%
        //
        // 内容超出后由 ScrollView 滚动
        // =========================================================
        dialog.window?.setLayout(
            (
                    context.resources.displayMetrics.widthPixels * 0.9f
                    ).toInt(),

            (
                    context.resources.displayMetrics.heightPixels * 0.5f
                    ).toInt()
        )

        // =========================================================
        // 判断是否需要显示「滑动查看更多」
        // =========================================================
        scrollView.post {

            tvScrollHint.visibility =
                if (scrollView.canScrollVertically(1)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        // =========================================================
        // forceShow = true
        //
        // 新增的输入框、输入校验、按钮限制全部不生效
        // =========================================================
        if (forceShow) {

            val positiveButton = dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            )

            positiveButton.setOnClickListener {
                dialog.dismiss()
            }

            return
        }

        // =========================================================
        // forceShow = false
        // 输入确认逻辑
        // =========================================================

        val editText = etConfirm ?: return
        val statusText = tvConfirmStatus ?: return

        val positiveButton = dialog.getButton(
            AlertDialog.BUTTON_POSITIVE
        )

        val negativeButton = dialog.getButton(
            AlertDialog.BUTTON_NEGATIVE
        )



        // =========================================================
        // 输入框获得焦点时
        // 自动滚动到输入框
        // =========================================================
        editText.setOnFocusChangeListener { _, hasFocus ->

            if (hasFocus) {

                editText.postDelayed({

                    scrollView.smoothScrollTo(
                        0,
                        editText.bottom
                    )

                }, 200)
            }
        }

        // =========================================================
        // 输入监听
        // =========================================================
        editText.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {

                    val input = s?.toString() ?: ""

                    when {

                        input.isEmpty() -> {

                            statusText.visibility = View.GONE

                            editText.background =
                                GradientDrawable().apply {

                                    setColor(Color.WHITE)

                                    setStroke(
                                        1,
                                        Color.GRAY
                                    )

                                    cornerRadius = 8f
                                }
                        }

                        input == confirmText -> {

                            statusText.visibility =
                                View.VISIBLE

                            statusText.text =
                                "✓ 输入正确"

                            statusText.setTextColor(
                                Color.rgb(
                                    46,
                                    125,
                                    50
                                )
                            )

                            editText.background =
                                GradientDrawable().apply {

                                    setColor(
                                        0xFFE8F5E9.toInt()
                                    )

                                    setStroke(
                                        2,
                                        Color.rgb(
                                            46,
                                            125,
                                            50
                                        )
                                    )

                                    cornerRadius = 8f
                                }
                        }

                        else -> {

                            statusText.visibility =
                                View.VISIBLE

                            statusText.text =
                                "✕ 输入内容不正确，请按照上面的文字完整输入"

                            statusText.setTextColor(
                                Color.rgb(
                                    211,
                                    47,
                                    47
                                )
                            )

                            editText.background =
                                GradientDrawable().apply {

                                    setColor(
                                        0xFFFFEBEE.toInt()
                                    )

                                    setStroke(
                                        2,
                                        Color.rgb(
                                            211,
                                            47,
                                            47
                                        )
                                    )

                                    cornerRadius = 8f
                                }
                        }
                    }
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        // =========================================================
        // 「我知道了」
        // =========================================================
        positiveButton.setOnClickListener {

            val input = editText.text.toString()

            when {
                input.isEmpty() -> {

                    statusText.visibility = View.VISIBLE
                    statusText.text = "⚠ 请先输入上面的确认文字"
                    statusText.setTextColor(
                        Color.rgb(211, 47, 47)
                    )

                    editText.requestFocus()

                    editText.post {
                        scrollView.smoothScrollTo(
                            0,
                            editText.bottom
                        )
                    }
                }

                input != confirmText -> {

                    statusText.visibility = View.VISIBLE
                    statusText.text =
                        "✕ 确认文字不正确，请完整输入上面的文字"
                    statusText.setTextColor(
                        Color.rgb(211, 47, 47)
                    )

                    editText.requestFocus()

                    editText.post {
                        scrollView.smoothScrollTo(
                            0,
                            editText.bottom
                        )
                    }
                }

                else -> {
                    // 输入正确
                    dialog.dismiss()
                }
            }
        }

        // =========================================================
        // 「不再显示」
        // =========================================================
        negativeButton.setOnClickListener {

            val input = editText.text.toString()

            when {
                input.isEmpty() -> {

                    statusText.visibility = View.VISIBLE
                    statusText.text =
                        "⚠ 请先输入上面的确认文字"
                    statusText.setTextColor(
                        Color.rgb(211, 47, 47)
                    )

                    editText.requestFocus()

                    editText.post {
                        scrollView.smoothScrollTo(
                            0,
                            editText.bottom
                        )
                    }
                }

                input != confirmText -> {

                    statusText.visibility = View.VISIBLE
                    statusText.text =
                        "✕ 确认文字不正确，请完整输入上面的文字"
                    statusText.setTextColor(
                        Color.rgb(211, 47, 47)
                    )

                    editText.requestFocus()

                    editText.post {
                        scrollView.smoothScrollTo(
                            0,
                            editText.bottom
                        )
                    }
                }

                else -> {

                    preferences.edit()
                        .putBoolean(
                            "dont_show_again",
                            true
                        )
                        .apply()

                    dialog.dismiss()
                }
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun show_Shizuku_Warn_Dialog(
        context: Context,
        forceShow: Boolean = false
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

        // =========================================================
        // ScrollView
        // =========================================================
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        // =========================================================
        // 根布局
        // =========================================================
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                24,
                24,
                24,
                24
            )

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(dialogView)

        // =========================================================
        // 标题
        // =========================================================
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

        // =========================================================
        // 提示文字
        // =========================================================
        val tvTMSG = TextView(context).apply {

            val text = "随着AI的发展,编程门槛降低,几乎人人都能手搓一个app了,所以自动化软件也越来越多了,导致平台加强了对自动化的检测,因此:" +
                        "\n在使用自动化软件的时候,可能会出现安全提示让你做题," +
                        "大概率不会被封,但为了稳妥起见(以防万一),可以多备几个小号(缺点：小号更容易触发风控，优点：就算最差的情况出现，重新注册一个就是),俗话说,手中有粮心中不慌" +
                        "\nPS:\n1.我自己也在用,而且用了好几年了,一直很稳定(本软件诞生的初衷就是给我自己用的), " +
                    "\n2.目前为止也没有人反馈说被封了的\n3.从8月开始平台加强了对无障碍服务的检测，推荐用shizuku 模式"

            val spannable = SpannableString(text)

            // =====================================================
            // 「点此查看详情」点击跳转
            // =====================================================
            val target = "点此查看详情"
            val start = text.indexOf(target)

            if (start >= 0) {

                val end = start + target.length

                spannable.setSpan(
                    object : ClickableSpan() {

                        override fun onClick(widget: View) {
                            try {

                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                        "https://mp.weixin.qq.com/s/ZKs_y2tcHyJMLJy3kwsiZg"
                                    )
                                )

                                context.startActivity(intent)

                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun updateDrawState(
                            ds: TextPaint
                        ) {
                            super.updateDrawState(ds)

                            ds.color = Color.BLUE
                            ds.isUnderlineText = true
                        }
                    },
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // =====================================================
            // 红色 + 加粗
            // =====================================================
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

            movementMethod = LinkMovementMethod.getInstance()

            highlightColor = Color.TRANSPARENT

            textSize = 14f

            setTextColor(Color.DKGRAY)

            setPadding(
                0,
                0,
                0,
                24
            )
        }

        dialogView.addView(
            tvTMSG,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================================================
        // 对比文字
        // =========================================================
        val tvBI = TextView(context).apply {

            text = "自动化技术原理对比"

            textSize = 20f

            setTextColor(Color.BLACK)

            setTypeface(null, Typeface.BOLD)

            gravity = Gravity.START

            setPadding(
                0,
                0,
                0,
                24
            )
        }

        dialogView.addView(
            tvBI,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================================================
        // 表格
        // =========================================================
        val tableLayout = TableLayout(context).apply {

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            isStretchAllColumns = true
        }

        // =========================================================
        // 表头
        // =========================================================
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

                setPadding(
                    16,
                    16,
                    16,
                    16
                )

                gravity = Gravity.CENTER

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(Color.BLACK)

                textSize = 14f
            }

            val params = TableRow.LayoutParams(
                0,
                TableRow.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }

            headerRow.addView(
                tv,
                params
            )
        }

        tableLayout.addView(headerRow)

        // =========================================================
        // 表格数据
        // =========================================================
        val data = listOf(
            listOf("无障碍", "中", "中"),
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

                    setPadding(
                        16,
                        16,
                        16,
                        16
                    )

                    gravity = Gravity.CENTER

                    setSingleLine(false)

                    ellipsize = null

                    textSize = 14f

                    // 第一列加粗
                    if (colIndex == 0) {
                        setTypeface(
                            null,
                            Typeface.BOLD
                        )
                    }

                    // 安全性高亮
                    if (colIndex > 1) {

                        setTextColor(
                            Color.parseColor("#FF5722")
                        )

                        setBackgroundColor(
                            0xFFFFF3E0.toInt()
                        )

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

                tableRow.addView(
                    tv,
                    params
                )
            }

            tableLayout.addView(tableRow)
        }

        dialogView.addView(tableLayout)

        // =========================================================
        // 总结
        // =========================================================
        val tvEnd = TextView(context).apply {

            val text =
                "总结: 随着平台对无障碍服务检测的加强，shizuku模式反而成了更好的选择了,但是鱼与熊掌不可兼得,我们在享受自动化便利的同时,不可避免的也要承担一定的风险," +
                        "如果你从小就是一个听话守规矩的老实人,那还是老老实实的自己手动操作吧"

            val spannable = SpannableString(text)

            // =====================================================
            // 红色 + 加粗
            // =====================================================
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

            highlight(
                "我们在享受自动化便利的同时,不可避免的也要承担一定的风险"
            )

            this.text = spannable

            textSize = 16f

            setTextColor(Color.BLACK)

            setPadding(
                0,
                30,
                0,
                24
            )
        }

        dialogView.addView(
            tvEnd,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================================================
        // 风险确认输入框
        // forceShow = true 时以下功能全部不生效
        // =========================================================
        val confirmText =
            "我已充分了解可能的风险，毕竟鱼与熊掌不可兼得"

        // =========================================================
        // 以下全部只在非 forceShow 时添加
        // =========================================================
        var etConfirm: EditText? = null
        var tvConfirmStatus: TextView? = null

        if (!forceShow) {

            // =====================================================
            // 提示
            // =====================================================
            val tvConfirmTip = TextView(context).apply {

                text = "请完整输入以下文字："

                textSize = 14f

                setTextColor(Color.DKGRAY)

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    8,
                    0,
                    8
                )
            }

            dialogView.addView(
                tvConfirmTip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // =====================================================
            // 要求输入的文字
            // =====================================================
            val tvConfirmText = TextView(context).apply {

                text = confirmText

                textSize = 14f

                setTextColor(Color.RED)

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    4,
                    0,
                    12
                )
            }

            dialogView.addView(
                tvConfirmText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // =====================================================
            // 输入框
            // =====================================================
            val editText = EditText(context).apply {

                hint = "请输入上面的文字"

                textSize = 14f

                setSingleLine(true)

                setPadding(
                    16,
                    12,
                    16,
                    12
                )

                background = GradientDrawable().apply {

                    setColor(Color.WHITE)

                    setStroke(
                        1,
                        Color.GRAY
                    )

                    cornerRadius = 8f
                }
            }

            etConfirm = editText

            dialogView.addView(
                editText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 4
                }
            )

            // =====================================================
            // 输入状态提示
            // =====================================================
            val statusText = TextView(context).apply {

                textSize = 13f

                setPadding(
                    4,
                    4,
                    4,
                    12
                )

                visibility = View.GONE
            }

            tvConfirmStatus = statusText

            dialogView.addView(
                statusText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // =========================================================
        // ScrollView + 右侧提示
        // =========================================================
        val scrollContainer = FrameLayout(context)

        scrollContainer.addView(
            scrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // =========================================================
        // 「滑动查看更多」提示
        // =========================================================
        val tvScrollHint = TextView(context).apply {

            text = "↕\n滑动到底关闭"

            textSize = 12f

            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER

            setPadding(
                8,
                10,
                8,
                10
            )

            background = GradientDrawable().apply {

                setColor(
                    0xCC555555.toInt()
                )

                cornerRadius = 20f
            }

            elevation = 8f

            visibility = View.GONE

            // 防止点击提示影响 ScrollView
            isClickable = false
            isFocusable = false
        }

        val hintParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {

            gravity =
                Gravity.END or Gravity.CENTER_VERTICAL

            rightMargin = 4
        }

        scrollContainer.addView(
            tvScrollHint,
            hintParams
        )

        // =========================================================
        // ScrollView 滚动监听
        // =========================================================
        scrollView.setOnScrollChangeListener {
                _,
                _,
                scrollY,
                _,
                _ ->

            val canScrollDown = scrollView.canScrollVertically(1)
            tvScrollHint.visibility =
                if (canScrollDown ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        // =========================================================
        // 创建 Dialog
        // =========================================================
        val builder = AlertDialog.Builder(context)
            .setView(scrollContainer)
            .setPositiveButton(
                "我知道了",
                null
            )

        // forceShow = true 时不显示“不再显示”
        if (!forceShow) {

            builder.setNegativeButton(
                "不再显示",
                null
            )
        }

        val dialog = builder.create()

        // =========================================================
        // 不允许点击空白关闭
        // =========================================================
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        // =========================================================
        // 键盘弹出时自动调整 Dialog
        // =========================================================
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        // =========================================================
        // Dialog 显示
        // =========================================================
        dialog.show()

        // =========================================================
        // Dialog 宽高
        //
        // 宽度：屏幕 90%
        // 高度：屏幕 50%
        //
        // 内容超出后由 ScrollView 滚动
        // =========================================================
        dialog.window?.setLayout(
            (
                    context.resources.displayMetrics.widthPixels * 0.9f
                    ).toInt(),

            (
                    context.resources.displayMetrics.heightPixels * 0.5f
                    ).toInt()
        )

        // =========================================================
        // 判断是否需要显示「滑动查看更多」
        // =========================================================
        scrollView.post {

            tvScrollHint.visibility =
                if (scrollView.canScrollVertically(1)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        // =========================================================
        // forceShow = true
        //
        // 新增的输入框、输入校验、按钮限制全部不生效
        // =========================================================
        if (forceShow) {

            val positiveButton = dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            )

            positiveButton.setOnClickListener {
                dialog.dismiss()
            }

            return
        }

        // =========================================================
        // forceShow = false
        // 输入确认逻辑
        // =========================================================

        val editText = etConfirm ?: return
        val statusText = tvConfirmStatus ?: return

        val positiveButton = dialog.getButton(
            AlertDialog.BUTTON_POSITIVE
        )

        val negativeButton = dialog.getButton(
            AlertDialog.BUTTON_NEGATIVE
        )



        // =========================================================
        // 输入框获得焦点时
        // 自动滚动到输入框
        // =========================================================
        editText.setOnFocusChangeListener { _, hasFocus ->

            if (hasFocus) {

                editText.postDelayed({

                    scrollView.smoothScrollTo(
                        0,
                        editText.bottom
                    )

                }, 200)
            }
        }

        // =========================================================
        // 输入监听
        // =========================================================
        editText.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {

                    val input = s?.toString() ?: ""

                    when {

                        input.isEmpty() -> {

                            statusText.visibility = View.GONE

                            editText.background =
                                GradientDrawable().apply {

                                    setColor(Color.WHITE)

                                    setStroke(
                                        1,
                                        Color.GRAY
                                    )

                                    cornerRadius = 8f
                                }
                        }

                        input == confirmText -> {

                            statusText.visibility =
                                View.VISIBLE

                            statusText.text =
                                "✓ 输入正确"

                            statusText.setTextColor(
                                Color.rgb(
                                    46,
                                    125,
                                    50
                                )
                            )

                            editText.background =
                                GradientDrawable().apply {

                                    setColor(
                                        0xFFE8F5E9.toInt()
                                    )

                                    setStroke(
                                        2,
                                        Color.rgb(
                                            46,
                                            125,
                                            50
                                        )
                                    )

                                    cornerRadius = 8f
                                }
                        }

                        else -> {

                            statusText.visibility =
                                View.VISIBLE

                            statusText.text =
                                "✕ 输入内容不正确，请按照上面的文字完整输入"

                            statusText.setTextColor(
                                Color.rgb(
                                    211,
                                    47,
                                    47
                                )
                            )

                            editText.background =
                                GradientDrawable().apply {

                                    setColor(
                                        0xFFFFEBEE.toInt()
                                    )

                                    setStroke(
                                        2,
                                        Color.rgb(
                                            211,
                                            47,
                                            47
                                        )
                                    )

                                    cornerRadius = 8f
                                }
                        }
                    }
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        // =========================================================
        // 「我知道了」
        // =========================================================
        positiveButton.setOnClickListener {

            val input = editText.text.toString()

            when {
                input.isEmpty() -> {

                    statusText.visibility = View.VISIBLE
                    statusText.text = "⚠ 请先输入上面的确认文字"
                    statusText.setTextColor(
                        Color.rgb(211, 47, 47)
                    )

                    editText.requestFocus()

                    editText.post {
                        scrollView.smoothScrollTo(
                            0,
                            editText.bottom
                        )
                    }
                }

                input != confirmText -> {

                    statusText.visibility = View.VISIBLE
                    statusText.text =
                        "✕ 确认文字不正确，请完整输入上面的文字"
                    statusText.setTextColor(
                        Color.rgb(211, 47, 47)
                    )

                    editText.requestFocus()

                    editText.post {
                        scrollView.smoothScrollTo(
                            0,
                            editText.bottom
                        )
                    }
                }

                else -> {
                    // 输入正确
                    dialog.dismiss()
                }
            }
        }

        // =========================================================
        // 「不再显示」
        // =========================================================
        negativeButton.setOnClickListener {

            val input = editText.text.toString()

            when {
                input.isEmpty() -> {

                    statusText.visibility = View.VISIBLE
                    statusText.text =
                        "⚠ 请先输入上面的确认文字"
                    statusText.setTextColor(
                        Color.rgb(211, 47, 47)
                    )

                    editText.requestFocus()

                    editText.post {
                        scrollView.smoothScrollTo(
                            0,
                            editText.bottom
                        )
                    }
                }

                input != confirmText -> {

                    statusText.visibility = View.VISIBLE
                    statusText.text =
                        "✕ 确认文字不正确，请完整输入上面的文字"
                    statusText.setTextColor(
                        Color.rgb(211, 47, 47)
                    )

                    editText.requestFocus()

                    editText.post {
                        scrollView.smoothScrollTo(
                            0,
                            editText.bottom
                        )
                    }
                }

                else -> {

                    preferences.edit()
                        .putBoolean(
                            "dont_show_again",
                            true
                        )
                        .apply()

                    dialog.dismiss()
                }
            }
        }
    }

}