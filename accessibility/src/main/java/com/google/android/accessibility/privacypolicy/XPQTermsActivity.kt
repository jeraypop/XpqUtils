package com.google.android.accessibility.privacypolicy

import com.android.accessibility.ext.R

/**
 * 用户协议页。地址由宿主经 [XpqPrivacyDialog] 传入（http(s) 网页或 asset 页），
 * 展示逻辑统一在 [BasePrivacyWebActivity]。
 */
class XPQTermsActivity : BasePrivacyWebActivity() {

    override val pageTitleRes: Int
        get() = R.string.xpqprivacy_title_terms

    override val pageUrl: String
        get() = XpqPrivacyDialog.xpqterms

    override val fallbackAssetFileName: String
        get() = XpqPrivacyDialog.default_Terms
}
