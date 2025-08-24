package com.google.android.accessibility.ext.wcapi

import com.android.accessibility.ext.BuildConfig


val getWCField: List<Pair<String, String>> = listOf(
    Pair(BuildConfig.W_C_PACKAGE.decrypt(), BuildConfig.W_C_PACKAGE.decrypt()+BuildConfig.W_C_LAUNCHER_UI.decrypt()),
    Pair(BuildConfig.W_C_SCAN.decrypt(), "0"),
    Pair(BuildConfig.USER_KEY.decrypt(), "%s"),
    Pair(BuildConfig.TALKER_KEY.decrypt(), "false"),
    Pair(BuildConfig.COUNT_KEY.decrypt(), "1"),
    Pair(BuildConfig.W_C_SCAN21.decrypt(), BuildConfig.W_C_SCAN22.decrypt()) ,
    Pair(BuildConfig.W_C_GZH.decrypt(), BuildConfig.W_C_ADDFRIEND.decrypt())
)

/*
* internal修饰符限制getZFBField只能在当前模块内访问
* */
internal val getZFBField: List<Pair<String, String>> = listOf(
    Pair(BuildConfig.INTENT_URL_FORMAT.decrypt(), BuildConfig.ALI_PAY_SCAN.decrypt()),
    Pair(BuildConfig.INTENT_URL_FORMAT.decrypt(), BuildConfig.ALI_PAY_BAR_CODE.decrypt())
)