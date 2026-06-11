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
     * 显示用户协议和隐私政策
     */
    @JvmStatic
    fun privacy_GuoNei_SJ(activity: Activity, privacy: String, terms: String) {
        val dialog = XpqPrivacyDialog(activity, privacy, terms)
        val tv_privacy_tips: TextView = dialog.findViewById(R.id.tv_privacy_tips)
        val btn_exit: TextView = dialog.findViewById(R.id.btn_exit)
        val btn_enter: TextView = dialog.findViewById(R.id.btn_enter)
        dialog.show()
        val string = activity.resources.getString(R.string.xpqprivacy_tips)
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
        btn_exit.setOnClickListener {
            dialog.dismiss()
            setCurrentVersionCode(getSelfAppInfo().versionCode)
            setPrivacyPolicy(false)

        }
        btn_enter.setOnClickListener {
            dialog.dismiss()
            setCurrentVersionCode(getSelfAppInfo().versionCode)
            setPrivacyPolicy(true)
            AliveUtils.toast(msg = activity.getString(R.string.xpqconfirmed))

        }
    }

    fun showAccessibilityAgreement(activity: Activity, example: String = activity.getString(R.string.gp_fullscreen_gesture)) {
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



            })
            privacyAgreementBinding.cancel.setOnClickListener(View.OnClickListener {
                alertDialog.dismiss()
                setPrivacyPolicy(false)
                //activity.finishAndRemoveTask()
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

