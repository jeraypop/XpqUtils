package com.lygttpod.android.auto



//import com.lygttpod.android.activity.result.api.observer.PermissionApi


import android.app.Activity
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

import com.google.android.accessibility.ext.activity.XpqBaseActivity
import com.google.android.accessibility.ext.fragment.SensitiveNotificationBottomSheet

import com.google.android.accessibility.ext.openAccessibilitySetting
import com.google.android.accessibility.ext.utils.ActivityUtils

import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appBuildTime
import com.google.android.accessibility.ext.utils.LoginDialog
import com.google.android.accessibility.ext.utils.NumberPickerDialog
import com.google.android.accessibility.ext.utils.broadcastutil.BroadcastOwnerType
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateCallback
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateReceiver
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager.CHANNEL_SCREEN
import com.google.android.accessibility.ext.utils.broadcastutil.UnifiedBroadcastManager.screenFilter
import com.google.android.accessibility.ext.view.FabMenuItem
import com.google.android.accessibility.ext.view.TaichiFabMenuView

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
import xpq.friend.R


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
            //LoginDialog(this) { phone, code ->
                // 调登录接口
            //}.show()




            //val webFragment = WebDialogFragment.newInstance("https://pay.ldxp.cn/shop/R24YFBFV/jtfovn")
           //webFragment.show(supportFragmentManager, "web_dialog")
            //webFragment.toggleDesktopMode()

            ActivityUtils.showKaWangDialog(activity = this,
                xiaopu = "https://pay.ldxp.cn/shop/R24YFBFV/jtfovn",
                weifk = "",
                isDan = false)


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

        // 创建匿名内部类实现 ScreenStateCallback 接口
        val screenStateCallback = object : ScreenStateCallback {
            override fun onScreenOff() {
                // 1️⃣ 屏幕熄灭
                // 一定 = 锁屏即将发生 / 已发生
                Log.e("监听屏幕啊", "ACTIVITY屏幕已关闭" )
            }

            override fun onScreenOn() {
                // 2️⃣ 屏幕点亮
                // ⚠️ 仍然可能在锁屏界面
                Log.e("监听屏幕啊", "ACTIVITY屏幕点亮" )
            }

            override fun onUserPresent() {
                // 3️⃣ 真正解锁完成（最重要）
                //disableKeyguard后,接收不到这个广播
                Log.e("监听屏幕啊", "ACTIVITY真正解锁完成" )
            }
        }
//        UnifiedBroadcastManager.register(
//            CHANNEL_SCREEN,
//            this,
//            BroadcastOwnerType.ACTIVITY,
//            this,
//            ScreenStateReceiver(screenStateCallback),
//            screenFilter,
//            lifecycleOwner = this
//        )


        val fabMenu = findViewById<TaichiFabMenuView>(R.id.fabMenu)

        fabMenu.setMenus(
            listOf(
                FabMenuItem("应用保活", com.android.accessibility.ext.R.drawable.ic_open_xpq) {
                    AliveUtils.openAliveActivity(true,false,NotificationListenerServiceImp::class.java)
                },
                FabMenuItem("视频播放", com.android.accessibility.ext.R.drawable.ic_close_xpq) {
                    ActivityUtils.showVideoDialog(this@MainActivity, "https://gitlab.com/mytiper/wechat/-/raw/master/public/unlock.mp4")
                },
                FabMenuItem("解锁方案", com.android.accessibility.ext.R.drawable.ic_close_xpq) {
                    NumberPickerDialog.showDefault(context = this@MainActivity)
                },
                FabMenuItem("充值会员", com.android.accessibility.ext.R.drawable.ic_close_xpq) {
                    ActivityUtils.showKaWangDialog(activity = this@MainActivity,
                        xiaopu = "https://pay.ldxp.cn/shop/R24YFBFV/jtfovn",
                        weifk = "",
                        isDan = false)
                }

            )
        )


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