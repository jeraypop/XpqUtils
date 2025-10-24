package com.google.android.accessibility.ext.activity

import android.content.Context
import android.content.Intent

import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

class LockScreenActivity : BaseLockScreenActivity() {
    companion object {
        @JvmOverloads
        @JvmStatic
        fun openLockScreenActivity(context: Context = appContext) {
            val i = Intent(context, LockScreenActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    override suspend fun doMyWork(i: Int) {
        // 如果你想自定义执行逻辑，覆写此方法
        super.doMyWork(i)
    }

    //覆盖密码方法
    override fun getUnlockPassword(): String? {
        // 从安全存储或运行时来源返回密码
        return "123456"
    }

    override fun handleIntent(intent: Intent) {
        super.handleIntent(intent)
    }
}

