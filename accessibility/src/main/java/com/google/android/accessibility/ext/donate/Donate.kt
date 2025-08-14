package com.google.android.accessibility.ext.donate

import android.content.Context
import android.content.Intent
import com.google.android.accessibility.ext.utils.MMKVConst.EXTRA_KEY_PAY_CONFIG

/**
 * Created by qiang on 2025/8/9.
 */
class Donate {
    companion object {
        @JvmStatic
        fun init(context: Context, donateConfig: DonateConfig) {
            val intent = Intent(context, DonateActivity::class.java)
            intent.putExtra(EXTRA_KEY_PAY_CONFIG, donateConfig)
            context.startActivity(intent)
        }
    }
}