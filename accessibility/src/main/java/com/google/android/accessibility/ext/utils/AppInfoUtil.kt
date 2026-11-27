package com.google.android.accessibility.ext.utils

import android.R.attr.fadingEdgeLength
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.XpqGpPrivacyAgreementBinding
import com.google.android.accessibility.ext.utils.KeyguardUnLock.getScreenSize
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.privacypolicy.XPQPrivacyPolicyActivity
import com.google.android.accessibility.privacypolicy.XPQTermsActivity
import com.google.android.accessibility.privacypolicy.XpqPrivacyDialog

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/5/23  15:32
 * Description:This is AppVersionUtil
 */
/**
 * App 版本信息工具类
 * 适用于库模块，可动态获取宿主应用 versionName 和 versionCode
 */
object AppInfoUtil {

    const val UNKNOWN = "unknown"

    /**
     * 获取指定包名的应用名称
     *
     * @param context 上下文（用于获取 PackageManager）
     * @param pkgName 目标应用包名
     * @return 应用名称，失败返回 UNKNOWN
     */
    @JvmStatic
    @JvmOverloads
    fun getAppName(context: Context = appContext, pkgName: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            UNKNOWN
        }
    }

    /**
     * 获取指定包名的 versionName
     *
     * @param context 上下文
     * @param pkgName 目标应用包名
     * @return versionName，失败返回 UNKNOWN
     */
    @JvmStatic
    @JvmOverloads
    fun getVersionName(context: Context = appContext, pkgName: String): String {
        return try {
            val info = context.packageManager.getPackageInfo(pkgName, 0)
            info.versionName ?: UNKNOWN
        } catch (e: Exception) {
            UNKNOWN
        }
    }

    /**
     * 获取指定包名的 versionCode（兼容 API 28+）
     *
     * @param context 上下文
     * @param pkgName 目标应用包名
     * @return versionCode，失败返回 0L
     */
    @JvmStatic
    @JvmOverloads
    fun getVersionCode(context: Context = appContext, pkgName: String): Long {
        return try {
            val info = context.packageManager.getPackageInfo(pkgName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取指定应用完整信息
     *
     * @param context 上下文
     * @param pkgName 目标应用包名
     * @return XPQ_AppInfo（包含应用名 + versionName + versionCode）
     */
    @JvmStatic
    @JvmOverloads
    fun getAppInfo(context: Context = appContext, pkgName: String): XPQ_AppInfo {
        return XPQ_AppInfo(
            appName = getAppName(context, pkgName),
            versionName = getVersionName(context, pkgName),
            versionCode = getVersionCode(context, pkgName)
        )
    }

    /**
     * 获取当前宿主应用信息
     *
     * @param context 上下文
     * @return 当前应用的 XPQ_AppInfo
     */
    @JvmStatic
    @JvmOverloads
    fun getSelfAppInfo(context: Context = appContext): XPQ_AppInfo {
        return getAppInfo(context, context.packageName)
    }

    /**
     * 获取当前语言
     *
     * @param context
     * @return 返回格式
     * zh-CN
     * en-US
     * ja-JP
     * zh-TW
     */
    @JvmStatic
    @JvmOverloads
    fun getLanguageTag(context: Context = appContext): String {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        return locale.toLanguageTag()
    }

    @JvmOverloads
    @JvmStatic
    fun setPrivacyPolicy(zqs: Boolean = false) {
        MMKVUtil.put(MMKVConst.KEY_PRIVACY_POLICY,zqs)
    }
    @JvmOverloads
    @JvmStatic
    fun getPrivacyPolicy(default: Boolean = false): Boolean {
        return MMKVUtil.get(MMKVConst.KEY_PRIVACY_POLICY,default)
    }

    @JvmOverloads
    @JvmStatic
    fun setCurrentVersionCode(zqs: Long = 0L) {
        MMKVUtil.put(MMKVConst.KEY_CURRENT_VERSION_CODE,zqs)
    }
    @JvmOverloads
    @JvmStatic
    fun getCurrentVersionCode(default: Long = 0L): Long {
        return MMKVUtil.get(MMKVConst.KEY_CURRENT_VERSION_CODE,default)
    }

    /**
     * 升级（versionCode 变了）之后是否要重新弹一次隐私政策。
     *
     * 各家商店的要求不一样：国内商店普遍要求「隐私政策变更时重新征得同意」，
     * 但按版本号判断属于宁严不宽——协议改没改都会弹，用户每次更新都要点一遍。
     * 所以统一收口到这里，宿主判断写法固定为：
     *
     * ```
     * !getPrivacyPolicy() || (needPrivacyOnUpgrade() && getCurrentVersionCode() != 当前版本号)
     * ```
     *
     * 默认 **false**：升级不打扰用户，只在从未同意过时弹。
     * 哪天某个商店强制要求了，把这里的返回值改成 true 即可，所有依赖本库的宿主一次生效，
     * 不用逐个改宿主代码。
     *
     * 注意：无论这里取值如何，用户点「同意」时都会照常写 [setCurrentVersionCode]，
     * 所以从 false 改成 true 之后，存量用户会各弹一次（记录的是上一次同意时的旧版本号）。
     */
    @JvmStatic
    fun needPrivacyOnUpgrade(): Boolean = false

    /**
     * 显示用户协议和隐私政策。
     *
     * 两种使用场景，靠 [isAbout] 区分：
     * - `isAbout = false`（默认，**首次启动**）：用户必须表态。底部显示「不同意 / 同意」两个按钮，
     *   除此之外没有任何关闭通道（返回键、点击空白处都被 dialog 自身的 `setCancelable(false)`
     *   + `setCanceledOnTouchOutside(false)` 挡掉）。点「不同意」= 不得继续使用 ⇒ 记录否定态后退出 App。
     * - `isAbout = true`（**从关于界面二次进入**）：只是「查看」协议，用户不需要再表态 ⇒ 标题换成
     *   [R.string.xpqprivacy_title_about]（「协议与政策」，不再是要求确认的「用户须知」）、正文换成
     *   [R.string.xpqprivacy_tips_about]、隐藏底部按钮、右上角露出 ✕；同时**放开**返回键与点击空白处，
     *   三条路都能关掉弹框。此时不写任何同意状态。
     *
     * 宿主拿结果用回调，不要再自己轮询 [getPrivacyPolicy]：
     * ```java
     * AppInfoUtil.privacy_GuoNei_SJ(activity, privacy, terms, false,
     *         () -> startMain(), () -> doNothing() /* exitOnCancel 默认 true，仍会退出 */);
     * ```
     *
     * @param activity 宿主 Activity
     * @param privacy 隐私政策地址；传空串走库内默认 assets
     * @param terms 用户协议地址；传空串走库内默认 assets
     * @param isAbout true = 从关于界面查看，不写同意状态、不阻断使用（此时两个回调都不会触发）
     * @param onAgree 点「同意」后的回调；传了就**不再弹库内的「已确认」Toast**，交给你自己提示
     * @param onCancel 点「不同意」后的回调；触发时同意状态已写成 false
     * @param exitOnCancel 「不同意」之后是否由库退出 App（默认 true，即「不同意就不得使用」）。
     *   想自己接管（例如先弹自己的挽留页）就传 false，否则回调执行完进程就被结束了
     */
    @JvmStatic
    @JvmOverloads
    fun privacy_GuoNei_SJ(
        activity: Activity,
        privacy: String,
        terms: String,
        isAbout: Boolean = false,
        onAgree: XpqPrivacyAction? = null,
        onCancel: XpqPrivacyAction? = null,
        exitOnCancel: Boolean = true
    ) {
        val dialog = XpqPrivacyDialog(activity, privacy, terms)
        val tv_note: TextView = dialog.findViewById(R.id.tv_note)
        val tv_privacy_tips: TextView = dialog.findViewById(R.id.tv_privacy_tips)
        val ll_buttons: LinearLayout = dialog.findViewById(R.id.ll_buttons)
        val btn_close: TextView = dialog.findViewById(R.id.btn_close)
        val btn_exit: TextView = dialog.findViewById(R.id.btn_exit)
        val btn_enter: TextView = dialog.findViewById(R.id.btn_enter)
        dialog.show()
        // 关于界面是「查看」而不是「表态」，文案换成中性的（两个协议名照旧可点开全文）
        val string = activity.resources.getString(
            if (isAbout) R.string.xpqprivacy_tips_about else R.string.xpqprivacy_tips
        )
        val key1 = activity.resources.getString(R.string.xpqprivacy_tips_key1)
        val key2 = activity.resources.getString(R.string.xpqprivacy_tips_key2)
        val index1 = string.indexOf(key1)
        val index2 = string.indexOf(key2)

        //需要显示的字串
        val spannedString = SpannableString(string)
        //设置点击字体颜色
        val colorSpan1 = ForegroundColorSpan(Color.BLUE)
        spannedString.setSpan(colorSpan1, index1, index1 + key1.length, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
        val colorSpan2 = ForegroundColorSpan(Color.BLUE)
        spannedString.setSpan(colorSpan2, index2, index2 + key2.length, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
        //设置点击字体大小
        val sizeSpan1 = AbsoluteSizeSpan(18, true)
        spannedString.setSpan(sizeSpan1, index1, index1 + key1.length, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
        val sizeSpan2 = AbsoluteSizeSpan(18, true)
        spannedString.setSpan(sizeSpan2, index2, index2 + key2.length, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
        //设置点击事件
        val clickableSpan1: ClickableSpan = object : ClickableSpan() {
            override fun onClick(view: View) {
                val intent = Intent(activity, XPQTermsActivity::class.java)
                activity.startActivity(intent)
            }

            override fun updateDrawState(ds: TextPaint) {
                //点击事件去掉下划线
                ds.isUnderlineText = false
            }
        }
        spannedString.setSpan(clickableSpan1, index1, index1 + key1.length, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
        val clickableSpan2: ClickableSpan = object : ClickableSpan() {
            override fun onClick(view: View) {
                val intent = Intent(activity, XPQPrivacyPolicyActivity::class.java)
                activity.startActivity(intent)
            }

            override fun updateDrawState(ds: TextPaint) {
                //点击事件去掉下划线
                ds.isUnderlineText = false
            }
        }
        spannedString.setSpan(clickableSpan2, index2, index2 + key2.length, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)

        //设置点击后的颜色为透明，否则会一直出现高亮
        tv_privacy_tips.highlightColor = Color.TRANSPARENT
        //开始响应点击事件
        tv_privacy_tips.movementMethod = LinkMovementMethod.getInstance()
        tv_privacy_tips.text = spannedString

        //设置弹框宽度占屏幕的80%
        val (width, height) = getScreenSize()
        val params: WindowManager.LayoutParams = dialog!!.getWindow()!!.getAttributes()
        params.width = (width * 0.80).toInt()
        dialog!!.getWindow()!!.setAttributes(params)

        if (isAbout) {
            // 关于界面进来的只「看」不「表态」：
            // 标题从「用户须知」（要求确认的口吻）换成「协议与政策」（纯查看），
            // 整行按钮（含中间分隔线）一起隐藏，否则会留下一条空白的 50dp 横条。
            tv_note.text = activity.getString(R.string.xpqprivacy_title_about)
            // 这一支只有一行短文案（两个协议名），居中比顶左好看；
            // 首次启动那支是多行长文本，保持左对齐不动。
            tv_privacy_tips.gravity = Gravity.CENTER_HORIZONTAL
            ll_buttons.visibility = View.GONE
            // 按钮藏了以后就没有任何可点的东西，必须换关闭通道：右上角 ✕（主）+ 返回键 / 点击空白处（习惯）。
            // XpqPrivacyDialog 构造里是 setCancelable(false)，后两条要在这里显式放开。
            btn_close.visibility = View.VISIBLE
            btn_close.setOnClickListener { dialog.dismiss() }
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
            return
        }

        // 首次启动：只能靠这两个按钮收场 —— dialog 构造里已 setCancelable(false)
        // + setCanceledOnTouchOutside(false)，返回键与空白处点击都关不掉。
        btn_exit.setOnClickListener {
            dialog.dismiss()
            setCurrentVersionCode(getSelfAppInfo().versionCode)
            setPrivacyPolicy(false)
            // 先给宿主回调（它可能要做收尾 / 记日志），再按 exitOnCancel 决定是否结束进程。
            // 宿主想自己接管退出流程（例如先弹挽留页）就传 exitOnCancel = false。
            runCatching { onCancel?.onAction() }
            if (exitOnCancel) {
                // 不同意 = 不得继续使用本应用
                exitApp(activity)
            }
        }
        btn_enter.setOnClickListener {
            dialog.dismiss()
            setCurrentVersionCode(getSelfAppInfo().versionCode)
            setPrivacyPolicy(true)
            // 宿主传了回调就由它自己提示，库不再多弹一个「已确认」Toast
            if (onAgree != null) {
                runCatching { onAgree.onAction() }
            } else {
                AliveUtils.toast(msg = activity.getString(R.string.xpqconfirmed))
            }
        }
    }

    /**
     * 退出应用：先把当前任务栈里的界面收掉，再结束本进程。
     *
     * `finishAffinity()` 只是让界面消失，进程还活着（宿主的 Service、Shizuku 连接等仍在跑），
     * 所以必须再结束进程才算真正「退出」。[android.os.Process.killProcess] 是立即生效的终结手段，
     * 后面的 [kotlin.system.exitProcess] 只是兜底（正常情况下执行不到）。
     */
    private fun exitApp(activity: Activity) {
        runCatching { activity.finishAffinity() }
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    /**
     * Google Play 渠道的隐私弹窗（不带协议全文链接，只有一段说明 + 同意/取消）。
     *
     * 与 [privacy_GuoNei_SJ] 的回调语义保持一致：回调在主线程、`dismiss()` 之后触发，
     * 触发时同意状态已写进 MMKV。
     *
     * @param onAgree 点「同意」后的回调
     * @param onCancel 点「取消」后的回调；触发时同意状态已写成 false
     * @param exitOnCancel 「取消」之后是否由库收掉任务栈（默认 true）。想自己接管就传 false
     */
    @JvmStatic
    @JvmOverloads
    fun showAccessibilityAgreement(
        activity: Activity,
        example: String = activity.getString(R.string.gp_fullscreen_gesture),
        onAgree: XpqPrivacyAction? = null,
        onCancel: XpqPrivacyAction? = null,
        exitOnCancel: Boolean = true
    ) {
        val str: String = activity.getString(R.string.gp_permission_content, example)
     
        try {
            val privacyAgreementBinding: XpqGpPrivacyAgreementBinding =
                XpqGpPrivacyAgreementBinding.inflate(
                    activity.layoutInflater)
            val alertDialog: AlertDialog =
                AlertDialog.Builder(activity).setCancelable(false)
                    .setView(privacyAgreementBinding.getRoot()).create()
            privacyAgreementBinding.content.setText(str)
            privacyAgreementBinding.sure.setOnClickListener(View.OnClickListener {
                setPrivacyPolicy(true)
                alertDialog.dismiss()
                runCatching { onAgree?.onAction() }

            })
            privacyAgreementBinding.cancel.setOnClickListener(View.OnClickListener {
                alertDialog.dismiss()
                setPrivacyPolicy(false)
                runCatching { onCancel?.onAction() }
                if (exitOnCancel) {
                    activity.finishAndRemoveTask()
                }
            })
            val window = alertDialog.window
            //window!!.setBackgroundDrawableResource(com.assistant.`fun`.R.drawable.add_data_background)
            alertDialog.show()
            val lp = window!!.attributes
            val metrics = activity.resources.displayMetrics
            lp.width = metrics.widthPixels / 5 * 4
            lp.height = metrics.heightPixels / 5 * 3
            window.attributes = lp
        } catch (e: Throwable) {

        }

    }


}

/**
 * 应用信息数据结构
 */
data class XPQ_AppInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Long
)

