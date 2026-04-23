package com.google.android.accessibility.inputmethod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.inputmethod.KeepAliveInputMethod.Companion.imeContext

class XpqTransparentActivity : AppCompatActivity() {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun startXpqTransparentActivity(context: Context = appContext) {

            val intent = Intent(context, XpqTransparentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK )
            }

            context.startActivity(intent)
        }

    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(com.android.accessibility.ext.R.layout.activity_lock_screen)

        //最小化activity 1像素
       /* val window = window
        window.setGravity(Gravity.START or Gravity.TOP)
        val params = window.attributes
        params.width = 1
        params.height = 1
        params.x = 0
        params.y = 0
        window.attributes = params
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )*/

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
        //finish()

    }
}