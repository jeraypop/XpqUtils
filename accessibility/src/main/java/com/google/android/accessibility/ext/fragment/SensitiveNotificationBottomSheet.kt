package com.google.android.accessibility.ext.fragment
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat.startActivity
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SensitiveNotificationBottomSheet : BottomSheetDialogFragment() {
    // 声明视图引用为成员变量，以便在各个方法中访问
    private lateinit var tvWhyTitle: TextView
    private lateinit var tvWhyContent: TextView
    private lateinit var tvWorkaroundTitle: TextView
    private lateinit var tvWorkaroundContent: TextView
    private lateinit var ivSensitive: ImageView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // 使用我们创建的布局文件
        return inflater.inflate(R.layout.dialog_sensitive_notification_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 绑定视图
        tvWhyTitle = view.findViewById(R.id.tv_why_title)
        tvWhyContent = view.findViewById(R.id.tv_why_content)
        tvWorkaroundTitle = view.findViewById(R.id.tv_workaround_title)
        tvWorkaroundContent = view.findViewById(R.id.tv_workaround_content)
        ivSensitive = view.findViewById(R.id.iv_sensitive)
        val btnOk: Button = view.findViewById(R.id.btn_ok)

        // 设置应用图标
        ivSensitive.setImageResource(requireContext().applicationInfo.icon)

        // 1. 设置原因内容的文本和链接功能
        val reasonText = getString(R.string.sensitive_notification_reason)
        val spannableString = createSpannableString(reasonText)
        tvWhyContent.text = spannableString
        tvWhyContent.movementMethod = LinkMovementMethod.getInstance()
        //tvWhyContent.isClickable = true

        // 2. 设置解决方案内容的文本
        val packageName = requireContext().packageName
        val appName = requireContext().applicationInfo.loadLabel(requireContext().packageManager).toString()
        val workaroundText = getString(R.string.sensitive_notification_workarounds, packageName, appName)
        val workaroundSpannableString = createWorkaroundSpannableString(workaroundText,packageName)
        tvWorkaroundContent.text = workaroundSpannableString
        tvWorkaroundContent.movementMethod = LinkMovementMethod.getInstance()
        
        // 3. 设置敏感通知指南文本和链接功能
        val guideText = getString(R.string.sensitive_notification_guide)
        val guideSpannableString = createGuideSpannableString(guideText)
        val tvSensitiveGuide: TextView = view.findViewById(R.id.tv_sensitive_guide)
        tvSensitiveGuide.text = guideSpannableString
        tvSensitiveGuide.movementMethod = LinkMovementMethod.getInstance()
        
        // --- 为什么被系统隐藏了? 区域点击事件 (默认展开) ---
        tvWhyTitle.setOnClickListener {
            toggleVisibility(tvWhyTitle, tvWhyContent, tvWorkaroundContent)
        }

        // --- 有没有什么办法绕过? 区域点击事件 (默认折叠) ---
        tvWorkaroundTitle.setOnClickListener {
            toggleVisibility(tvWorkaroundTitle, tvWorkaroundContent, tvWhyContent)
        }

        // 确认按钮点击事件
        btnOk.setOnClickListener {
            dismiss() // 关闭对话框
        }

        // 初始化标题图标（根据默认可见性）
        updateTitleIcon(tvWhyTitle, tvWhyContent.visibility)
        updateTitleIcon(tvWorkaroundTitle, tvWorkaroundContent.visibility)
    }

    /**
     * 切换内容可见性并更新标题上的展开/折叠图标 (►/▼)
     * 同时控制另一个内容区域的显示/隐藏
     */
    private fun toggleVisibility(titleView: TextView, contentView: TextView, otherContentView: TextView) {
        if (contentView.visibility == View.VISIBLE) {
            contentView.visibility = View.GONE
            updateTitleIcon(titleView, View.GONE)
        } else {
            contentView.visibility = View.VISIBLE
            // 只隐藏另一个内容区域，不隐藏标题
            otherContentView.visibility = View.GONE
            updateTitleIcon(titleView, View.VISIBLE)
            // 更新另一个标题的图标为折叠状态
            val otherTitle = if (titleView.id == com.android.accessibility.ext.R.id.tv_why_title) tvWorkaroundTitle else tvWhyTitle
            updateTitleIcon(otherTitle, View.GONE)
        }
    }

    /**
     * 更新标题文本前的展开/折叠图标
     */
    private fun updateTitleIcon(titleView: TextView, visibility: Int) {
        // 移除当前的图标
        val currentText = titleView.text.toString().substring(2)
        if (visibility == View.VISIBLE) {
            titleView.text = "▼ $currentText"
        } else {
            titleView.text = "► $currentText"
        }
    }

    /**
     * 创建带有可点击链接的SpannableString
     */
    private fun createSpannableString(text: String): SpannableString {
        val spannableString = SpannableString(text)
        
        // 定义"安全角度"的点击事件
        val securityClickSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                goToNet("https://security.googleblog.com/2024/05/io-2024-whats-new-in-android-security.html")
            }
        }
        
        // 定义"issue tracker (英文)"的点击事件
        val issueTrackerClickSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                goToNet("https://issuetracker.google.com/issues/354524657")
            }
        }
        
        // 查找并设置"安全角度"的点击范围
        val reason1 = getString(R.string.sensitive_notification_reason_1)
        val securityStartIndex = text.indexOf(reason1)
        if (securityStartIndex >= 0) {
            spannableString.setSpan(securityClickSpan, securityStartIndex, securityStartIndex + reason1.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // 查找并设置"issue tracker (英文)"的点击范围
        val reason2 = getString(R.string.sensitive_notification_reason_2)
        val issueTrackerStartIndex = text.indexOf(reason2)
        if (issueTrackerStartIndex >= 0) {
            spannableString.setSpan(issueTrackerClickSpan, issueTrackerStartIndex, issueTrackerStartIndex + reason2.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        return spannableString
    }
    
    /**
     * 创建解决方案文本的SpannableString
     */
    private fun createWorkaroundSpannableString(text: String,pkg: String): SpannableString {
        val spannableString = SpannableString(text)
        val workaround2 = getString(R.string.sensitive_notification_workarounds_2, pkg)
        // 定义 如何执行 ADB 命令 的点击事件
        val doadbClickSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                goToNet("https://webadb.com")
            }
        }
        // 定义"adb shell appops set qu.zhu.li RECEIVE_SENSITIVE_NOTIFICATIONS allow"的点击事件
        val adbCommandClickSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                // 复制ADB命令到剪贴板
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ADB Command", workaround2)
                clipboard.setPrimaryClip(clip)
                AliveUtils.toast(msg = "ADB命令已复制到剪贴板")
            }
        }
        
        // 定义"App Ops"的点击事件
        val appOpsClickSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                // 可以链接到App Ops应用或保持空实现
                AliveUtils.toast(msg = "App Ops应用")
                goToNet("https://play.google.com/store/apps/details?id=rikka.appops")
            }
        }
        
        // 定义"Shizuku"的点击事件
        val shizukuClickSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                // 可以链接到Shizuku应用或保持空实现
                AliveUtils.toast(msg = "Shizuku应用")
                goToNet("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
            }
        }
        // 如何执行 ADB 命令 的点击范围
        val workaround1 = getString(R.string.sensitive_notification_workarounds_1)
        val doadb = text.indexOf(workaround1)
        if (doadb >= 0) {
            spannableString.setSpan(doadbClickSpan, doadb, doadb + workaround1.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // adb shell 命令的点击范围

        val adbCommandStartIndex = text.indexOf(workaround2)
        if (adbCommandStartIndex >= 0) {
            spannableString.setSpan(adbCommandClickSpan, adbCommandStartIndex, adbCommandStartIndex + workaround2.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // 查找并设置"App Ops"的点击范围
        val workaround3 = getString(R.string.sensitive_notification_workarounds_3)
        val appOpsStartIndex = text.indexOf(workaround3)
        if (appOpsStartIndex >= 0) {
            spannableString.setSpan(appOpsClickSpan, appOpsStartIndex, appOpsStartIndex + workaround3.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // 查找并设置"Shizuku"的点击范围
        val workaround4 = getString(R.string.sensitive_notification_workarounds_4)
        val shizukuStartIndex = text.indexOf(workaround4)
        if (shizukuStartIndex >= 0) {
            spannableString.setSpan(shizukuClickSpan, shizukuStartIndex, shizukuStartIndex + workaround4.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // 查找并设置"重启"的点击范围
        val workaround5 = getString(R.string.sensitive_notification_workarounds_5)
        val restartStartIndex = text.indexOf(workaround5)
        if (restartStartIndex >= 0) {
            spannableString.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    //AliveUtils.toast(msg = "需要重启手机使设置生效")
                }
            }, restartStartIndex, restartStartIndex + workaround5.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        return spannableString
    }
    
    /**
     * 创建指南文本的SpannableString
     */
    private fun createGuideSpannableString(text: String): SpannableString {
        val spannableString = SpannableString(text)

        // 定义"点我查看更详细教程"的点击事件
        val guideClickSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                goToNet("https://mp.weixin.qq.com/s/hW2wHPgK1o7IjLkrbdITVg")
            }
        }

        // 设置整个文本都是可点击的
        spannableString.setSpan(guideClickSpan, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        return spannableString
    }

    fun goToNet(str: String) {
        val uri = Uri.parse(str)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(requireContext(), intent, null)
    }
    
    companion object {
        const val TAG = "SensitiveNotificationSheet"
    }
}