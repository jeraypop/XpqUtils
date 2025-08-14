package com.google.android.accessibility.ext.donate

import androidx.annotation.DrawableRes
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.MMKVConst.ALI_KEY
import java.io.Serializable

/**
 * Created by qiang on 2025/8/9.
 */
class DonateConfig private constructor(builder: Builder) : Serializable {
    val wechatTip: String
    val aliTip: String
    val wechatSao: String
    val aliSao: String
    @DrawableRes
    val wechatQaImage: Int
    @DrawableRes
    val aliQaImage: Int
    val aliZhiKey: String //支付宝支付码，可从支付二维码中获取

    init {
        this.wechatQaImage = builder.wechatQaImage
        this.aliQaImage = builder.aliQaImage
        this.wechatTip = builder.wechatTip
        this.aliTip = builder.aliTip
        this.wechatSao = builder.wechatSao
        this.aliSao = builder.aliSao
        this.aliZhiKey = builder.aliZhiKey
    }

    // Getters
//    fun getWechatTip(): String {
//        return wechatTip
//    }

//    fun getAliTip(): String {
//        return aliTip
//    }

//    fun getWechatSao(): String {
//        return wechatSao
//    }

//    fun getAliSao(): String {
//        return aliSao
//    }

//    @DrawableRes
//    fun getWechatQaImage(): Int {
//        return wechatQaImage
//    }

//    @DrawableRes
//    fun getAliQaImage(): Int {
//        return aliQaImage
//    }

//    fun getAliZhiKey(): String {
//        return aliZhiKey
//    }

    class Builder {
        var wechatTip: String = ""
            private set
        var aliTip: String = ""
            private set
        var wechatSao: String = ""
            private set
        var aliSao: String = ""
            private set
        @DrawableRes
        var wechatQaImage: Int
            private set
        @DrawableRes
        var aliQaImage: Int
            private set
        var aliZhiKey: String
            private set

        constructor() {
            wechatQaImage = R.drawable.ic_weixin
            aliQaImage = R.drawable.ic_zhifubao
            aliZhiKey = ALI_KEY
        }

        constructor(aliKey: String) {
            wechatQaImage = R.drawable.ic_weixin
            aliQaImage = R.drawable.ic_zhifubao
            aliZhiKey = aliKey ?: ALI_KEY
        }

        constructor(aliKey: String, @DrawableRes qaAli: Int, @DrawableRes qaWechat: Int) {
            wechatQaImage = qaWechat
            aliQaImage = qaAli
            aliZhiKey = aliKey ?: ALI_KEY
        }

        fun setWechatTip(tip: String): Builder {
            wechatTip = tip
            return this
        }

        fun setAliTip(tip: String): Builder {
            aliTip = tip
            return this
        }

        fun setWechatSao(tip: String): Builder {
            wechatSao = tip
            return this
        }

        fun setAliSao(tip: String): Builder {
            aliSao = tip
            return this
        }

        fun build(): DonateConfig {
            // 构建，返回一个新对象
            return DonateConfig(this)
        }
    }
}