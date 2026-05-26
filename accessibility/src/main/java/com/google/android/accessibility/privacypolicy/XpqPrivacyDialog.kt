package com.google.android.accessibility.privacypolicy

import android.app.Dialog
import android.content.Context
import android.text.TextUtils
import com.android.accessibility.ext.R

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/5/26  3:34
 * Description:This is XpqPrivacyDialog
 */
class XpqPrivacyDialog(
    context: Context, privacy: String, terms: String
) : Dialog(context, R.style.XPQPrivacyThemeDialog) {

    companion object {
        const val LANGUAGE_CN = "zh-CN"
        const val ANDROID_ASSET = "file:///android_asset/"
        const val default_Privacy = "xpqlib_privacy.html"
        const val default_Terms = "xpqlib_terms.html"

        var xpqprivacypolicy: String = ANDROID_ASSET + default_Privacy
        var xpqterms: String = ANDROID_ASSET + default_Terms

    }

    init {
        setContentView(R.layout.xpqdialog_privacy)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        if (!TextUtils.isEmpty(privacy)){
            xpqprivacypolicy = privacy
        }
        if (!TextUtils.isEmpty(terms)){
            xpqterms = terms
        }

    }
}