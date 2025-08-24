package com.google.android.accessibility.ext.wcapi

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.accessibility.ext.BuildConfig


//scan
internal fun goWeChatLauncherUI(): Intent {
    return Intent().also {
        it.component = ComponentName(
            getWCField[0].first.restoreAllIllusion(),
            getWCField[0].second.restoreAllIllusion()
        )
        it.putExtra(
            getWCField[1].first.restoreAllIllusion(),
            getWCField[1].second.restoreAllIllusion() == "0"
        )
        it.putExtra(
            getWCField[5].first.restoreAllIllusion(),
            getWCField[5].second.restoreAllIllusion()
        )
    }
}

internal fun goWeChatLauncherUI(originalId:String):Intent{
    return Intent().also {
        it.component = ComponentName(
            getWCField[0].first.restoreAllIllusion(),
            getWCField[0].second.restoreAllIllusion()
        )
        it.putExtra(
            getWCField[2].first.restoreAllIllusion(),
            getWCField[2].second.restoreAllIllusion().format(originalId)
        )
        it.putExtra(
            getWCField[3].first.restoreAllIllusion(),
            getWCField[3].second.restoreAllIllusion().toBoolean()
        )
        it.putExtra(
            getWCField[4].first.restoreAllIllusion(),
            getWCField[4].second.restoreAllIllusion().toInt()
        )
    }
}

@SuppressLint("NewApi")
internal fun goAliPay(urlCode: String): Intent {
    return Intent.parseUri(
        getZFBField[0].first.replace("{urlCode}", urlCode).restoreAllIllusion(),
        Intent.URI_INTENT_SCHEME
    )
}

internal fun goAliPayScan(): Intent {
    return Intent().also {
        it.data = Uri.parse(getZFBField[0].second.restoreAllIllusion())
    }
}


internal fun goAliPayBarCode(): Intent {
    return Intent().also {
        it.data = Uri.parse(getZFBField[1].second.restoreAllIllusion())
    }
}


internal fun payIntent(context: Context, config: PayConfig): Intent {
    return Intent(context, ZhiActivity::class.java).also {
        it.putExtra(BuildConfig.EXTRA_KEY_PAY_CONFIG.restoreAllIllusion(), config)
    }
}

