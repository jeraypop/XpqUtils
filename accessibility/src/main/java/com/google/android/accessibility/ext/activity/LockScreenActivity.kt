package com.google.android.accessibility.ext.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.ActivityLockScreenBinding
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

class LockScreenActivity : XpqBaseActivity<ActivityLockScreenBinding>(
    bindingInflater = ActivityLockScreenBinding::inflate
) {
    companion object {
        @JvmOverloads
        @JvmStatic
        fun openLockScreenActivity(context: Context = appContext) {
            val i = Intent(context, LockScreenActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        KeyguardUnLock.showWhenLockedAndTurnScreenOn(this@LockScreenActivity)
    }
    override fun onResume() {
        super.onResume()
        // 在可见时再尝试一次，确保在解锁流程中的交互是最新的
        KeyguardUnLock.showWhenLockedAndTurnScreenOn(this@LockScreenActivity)
    }

    override fun initView_Xpq() {

    }

    override fun initData_Xpq() {

    }
}