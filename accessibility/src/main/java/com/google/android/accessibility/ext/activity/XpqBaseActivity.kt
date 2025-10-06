package com.google.android.accessibility.ext.activity

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.viewbinding.ViewBinding
import com.google.android.accessibility.ext.utils.AliveUtils

/**
 * 通用 XpqBaseActivity，支持两种布局方式（强制二选一）：
 * 1. ViewBinding:
 *    class MainActivity : XpqBaseActivity<ActivityMainBinding>(bindingInflater = ActivityMainBinding::inflate)
 * 2. layoutId:
 *    class MainActivity : XpqBaseActivity<ActivityMainBinding>(layoutId = R.layout.activity_main)
 *
 *
 *
 * 继承 AppCompatActivity 和 Activity 区别
 * 1️⃣ Activity
 *
 * 这是 Android Framework 原生类 (android.app.Activity)。
 *
 * 提供 Activity 的基本生命周期管理（onCreate、onResume 等）。
 *
 * 没有额外的兼容支持，功能上比较“干净”。
 *
 * 适合写系统级 App 或特殊场景，不依赖 Support 库。
 *
 * 2️⃣ AppCompatActivity
 *
 * 来自 AndroidX AppCompat 库 (androidx.appcompat.app.AppCompatActivity)。
 *
 * 它本质上继承了 FragmentActivity，再间接继承 ComponentActivity，再最终继承 Activity。
 *
 * 提供了一系列 向后兼容特性，主要有：
 *
 * Toolbar / ActionBar 兼容支持
 *
 * 可以用 setSupportActionBar(toolbar)，替代原生 ActionBar，样式更灵活。
 *
 * Theme.AppCompat 主题支持
 *
 * 允许使用 MaterialComponents 风格。
 *
 * AppCompatDelegate
 *
 * 自动夜间模式（Dark Mode）
 *
 * 资源兼容（如 VectorDrawableCompat）
 *
 * Fragment 支持
 *
 * 内部用的是 androidx.fragment.app.FragmentManager，支持更强大的 Fragment API。
 *
 * 旧版本兼容性
 *
 * 即使运行在 Android 5.0 或更低版本上，也能使用较新的控件和主题。
 *
 * ✅ 结论：
 * 几乎所有常规 Android 应用（尤其是用 ViewBinding、MaterialToolbar、Fragment 的）
 * 都应该继承 AppCompatActivity。
 * 只有在做 系统 App / AOSP 开发 / 特殊轻量场景 时，才会用 Activity。
 *
 */
abstract class XpqBaseActivity<VB : ViewBinding>(
    private val bindingInflater: ((LayoutInflater) -> VB)? = null,
    @LayoutRes private val layoutId: Int? = null
) : AppCompatActivity() {

    companion object {
        private const val TAG = "XpqBaseActivity"
    }

    private var _binding: VB? = null
    protected fun binding(): VB? = _binding
    protected fun requireBinding(): VB =
        _binding ?: throw IllegalStateException("Binding not initialized. Did you use layoutId mode?")

    private lateinit var singlePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var multiPermissionLauncher: ActivityResultLauncher<Array<String>>

    private var onPermissionResultSingle: ((Boolean) -> Unit)? = null
    private var onPermissionResultMultiple: ((Map<String, Boolean>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ----------- 强制约束：必须二选一 ----------
        if ((bindingInflater == null && layoutId == null) || (bindingInflater != null && layoutId != null)) {
            throw IllegalStateException(
                "❌ XpqBaseActivity 初始化错误：必须二选一 (bindingInflater 或 layoutId)，不能都为空或都设置！"
            )
        }

        if (bindingInflater != null) {
            _binding = bindingInflater.invoke(layoutInflater)
            setContentView(_binding!!.root)
        } else {
            setContentView(layoutId!!)
        }

        Log.d(TAG, "${this::class.java.simpleName} -> onCreate")

        singlePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                onPermissionResultSingle?.invoke(granted)
            }
        multiPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                onPermissionResultMultiple?.invoke(result)
            }

        initView_Xpq()
        initData_Xpq()
        AliveUtils.requestUpdateKeepAliveByTaskHide(AliveUtils.getKeepAliveByTaskHide())
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return super.onKeyUp(keyCode, event)
            }

            if (AliveUtils.getKeepAliveByTaskHide()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return
        }
        if (AliveUtils.getKeepAliveByTaskHide()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        }



    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // ---------- abstract ----------
    protected abstract fun initView_Xpq()
    protected abstract fun initData_Xpq()

    // ---------- helpers ----------
    protected fun showToast(text: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, text, duration).show()
    }

    protected fun runOnUi(action: () -> Unit) {
        if (isDestroyed || isFinishing) return
        runOnUiThread(action)
    }

    protected fun setupToolbar(toolbar: Toolbar, title: String? = null, showBack: Boolean = true) {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            this.title = title
            setDisplayHomeAsUpEnabled(showBack)
        }
        if (showBack) toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    // ---------- permissions ----------
    protected fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        onPermissionResultSingle = onResult
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) onResult(true) else singlePermissionLauncher.launch(permission)
    }

    protected fun requestPermissions(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
        onPermissionResultMultiple = onResult
        val allGranted = permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            onResult(permissions.associateWith { true })
        } else {
            multiPermissionLauncher.launch(permissions)
        }
    }

    protected fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
