package com.lygttpod.android.auto



//import com.lygttpod.android.activity.result.api.observer.PermissionApi


import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.MutableLiveData
import androidx.navigation.ui.AppBarConfiguration
import com.android.accessibility.ext.BuildConfig
import com.google.android.accessibility.ext.activity.LockScreenActivity
import com.google.android.accessibility.ext.activity.XpqBaseActivity
import com.google.android.accessibility.ext.fragment.SensitiveNotificationBottomSheet
import com.google.android.accessibility.ext.openAccessibilitySetting
import com.google.android.accessibility.ext.utils.ActivityUtils

import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appBuildTime
import com.google.android.accessibility.ext.utils.NumberPickerDialog

import com.google.android.accessibility.ext.wcapi.PayConfig
import com.google.android.accessibility.ext.wcapi.decrypt
import com.google.android.accessibility.ext.wcapi.encrypt
import com.google.android.accessibility.ext.wcapi.getWCField
import com.google.android.accessibility.ext.wcapi.openDonate
import com.google.android.accessibility.ext.wcapi.openWeChatToFollowInterface
import com.google.android.accessibility.ext.wcapi.restoreAllIllusion
import com.google.android.accessibility.ext.window.OverlayLog
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.lygttpod.android.auto.notification.NotificationListenerServiceImp


import xpq.friend.databinding.ActivityMainBinding


class MainActivity : XpqBaseActivity<ActivityMainBinding>(
    bindingInflater = ActivityMainBinding::inflate
) {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val accServiceLiveData = MutableLiveData<Boolean>()

    private var windowManager: WindowManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
//        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusBarBackground.layoutParams.height = systemBars.top
            binding.content.setPadding(0, systemBars.top, 0, 0)
            insets
        }
       

//        val navController = findNavController(R.id.nav_host_fragment_content_main)
//        appBarConfiguration = AppBarConfiguration(navController.graph)
//        setupActionBarWithNavController(navController, appBarConfiguration)
        windowManager = getSystemService<WindowManager>(WindowManager::class.java)
        accServiceLiveData.observe(this) { open ->

        }

        binding.fab.setOnClickListener {
//            AliveUtils.easyPermission(this@MainActivity)
            //OverlayLog.show()
            Thread {
                //throw RuntimeException("这是一个后台线程异常测试")
            }.start()

            val decrypt = BuildConfig.INTENT_URL_FORMAT.decrypt()
            val de = decrypt.restoreAllIllusion()
            val encrypt = "L7763^I^LOVE^YOU^66664".encrypt()
            Log.e("解密字符串", "decrypt=: "+ decrypt)
            Log.e("解密字符串", "de=: "+ de)
            Log.e("解密字符串", "encrypt=: "+ encrypt)
            val buildTimeMillis: Long = BuildConfig.BUILD_TIME
            // 如果要格式化输出：
            val date = java.util.Date(buildTimeMillis)
            val formatted = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(date)

            println("构建时间戳: $appBuildTime")
            println("构建时间: $formatted")
           //LockScreenActivity.openLockScreenActivity()

            //ActivityUtils.showVideoDialog(this, "https://gitlab.com/mytiper/wechat/-/raw/master/public/unlock.mp4")
            NumberPickerDialog.showDefault(context = this)

        }

        binding.btnZan.setOnClickListener{
            openDonate(
                PayConfig()
//                PayConfig(
//                    "fkx11204qu3e298yblfpx51",
//                    R.mipmap.alipay, R.mipmap.wechat
//                )
            )

//            Donate.init(
//                this,
//                DonateConfig.Builder().build()
////                DonateConfig.Builder("fkx11204qu3e298yblfpx51", R.mipmap.ic_zhifubao, R.mipmap.ic_weixin).build()
//            )
        }

        binding.btnAlive.setOnClickListener{
            AliveUtils.openAliveActivity(true,false,NotificationListenerServiceImp::class.java)
        }
        binding.btnAccessibility.setOnClickListener{
            AliveUtils.openAccessibility(this, SelectToSpeakService::class.java)
        }
        binding.btnNotification.setOnClickListener{

            AliveUtils.openNotificationListener(this, NotificationListenerServiceImp::class.java)
        }
        binding.btnGZH.setOnClickListener{
            //公众号ID

            openWeChatToFollowInterface(getWCField[6].first.restoreAllIllusion())
        }
        binding.btnAddFriend.setOnClickListener{
            //好友微信号
            //openWeChatToFollowInterface(getWCField[6].second.restoreAllIllusion())
            //openAccessibilitySetting()
            // 使用 FragmentManager 来显示 BottomSheetDialogFragment
            val sheet = SensitiveNotificationBottomSheet()
            sheet.show(supportFragmentManager, SensitiveNotificationBottomSheet.TAG)
        }


    }

    override fun initView_Xpq() {

    }

    override fun initData_Xpq() {

    }

    override fun onResume() {
        super.onResume()
//        WXAccessibility.isInWXApp.set(false)
//        accServiceLiveData.value =
//            isAccessibilityOpened(WXAccessibility::class.java)
    }

//    override fun onSupportNavigateUp(): Boolean {
//        val navController = findNavController(xpq.friend.R.id.nav_host_fragment_content_main)
//        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
//    }


}