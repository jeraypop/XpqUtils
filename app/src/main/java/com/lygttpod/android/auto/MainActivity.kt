package com.lygttpod.android.auto



//import com.lygttpod.android.activity.result.api.observer.PermissionApi


import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.ui.AppBarConfiguration
import com.mqd.updatelib.UpdateManager
import com.mqd.updatelib.core.UpdateRepository
import com.mqd.updatelib.core.UpdateState
import com.mqd.updatelib.download.ApkInstaller
import com.mqd.updatelib.ui.UpdateDialogHelper
import com.android.accessibility.ext.BuildConfig
import com.google.android.accessibility.ext.acc.EngineMode
import com.google.android.accessibility.ext.acc.XpqAcc
import com.google.android.accessibility.ext.acc.clickByText

import com.google.android.accessibility.ext.activity.XpqBaseActivity
import com.google.android.accessibility.ext.fragment.SensitiveNotificationBottomSheet

import com.google.android.accessibility.ext.utils.ActivityUtils

import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.AppInfoUtil
import com.google.android.accessibility.ext.utils.AppInfoUtil.privacy_GuoNei_SJ
import com.google.android.accessibility.ext.utils.JieSuoUtils
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appBuildTime
import com.google.android.accessibility.ext.music.MusicPlayer
import com.google.android.accessibility.ext.music.MusicStore
import com.google.android.accessibility.ext.utils.KeyguardUnLock.setShowClickIndicator
import com.google.android.accessibility.ext.utils.LoginDialog
import com.google.android.accessibility.ext.utils.NetworkHelperFullSmart
import com.google.android.accessibility.ext.utils.NetworkHelperFullSmart.intervalIsDuan
import com.google.android.accessibility.ext.utils.NumberInputSDK
import com.google.android.accessibility.ext.utils.NumberPickerDialog
import com.google.android.accessibility.ext.utils.XPQAccUtils.show_AC_Warn_Dialog
import com.google.android.accessibility.ext.utils.broadcastutil.ScreenStateCallback
import com.google.android.accessibility.ext.utils.safecheck.SafeTouchListener
import com.google.android.accessibility.ext.utils.safecheck.ScriptTouchDetector
import com.google.android.accessibility.ext.utils.safecheck.TouchBehaviorAnalyzer
import com.google.android.accessibility.ext.utils.safecheck.TouchGuardDelegate
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
import com.google.android.accessibility.privacypolicy.XpqPrivacyDialog.Companion.ANDROID_ASSET
import com.google.android.accessibility.privacypolicy.XpqPrivacyDialog.Companion.default_Privacy
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.google.android.accessibility.selecttospeak.accessibilityService
import com.lygttpod.android.auto.notification.NotificationListenerServiceImp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xpq.friend.R


import xpq.friend.databinding.ActivityMainBinding


class MainActivity : XpqBaseActivity<ActivityMainBinding>(
    bindingInflater = ActivityMainBinding::inflate
) {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val accServiceLiveData = MutableLiveData<Boolean>()

    private var windowManager: WindowManager? = null
    // 第一道：全局兜底，覆盖所有 View（包括没单独包装 onTouch 的）
    private val touchGuard = TouchGuardDelegate()

    //① 全局 第一道拦截：事件刚进 Activity 就判定，命中即吞掉，不再向下分发
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return if (touchGuard.shouldIntercept(ev)) {
            true                       // 脚本触摸，截断；下面 onTouch/onClick 都不触发
        } else {
            super.dispatchTouchEvent(ev)
        }
    }
    /**
     *  行为时序分析：喂入事件，UP 时判定是否像脚本注入。
     *
     * 返回语义与 OnTouchListener 对齐：
     *   - DOWN / MOVE  返回 false，继续放行（MOVE 会走 SafeTouchListener 的 else 分支进来）
     *   - UP 且疑似脚本 返回 true，消费事件 → onClick 不触发
     *   - UP 且真人     返回 false，放行 → onClick 触发
     */
    private fun traceTouch(event: MotionEvent): Boolean {
        val session = TouchBehaviorAnalyzer.dealOnTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                Log.e("调用栈", "DOWN  屏幕坐标=(${event.rawX}, ${event.rawY})")
            }
            MotionEvent.ACTION_UP -> {
                session?.let {
                    val moved = it.moveCount > 0 || it.distance > 0f
                    Log.e(
                        "调用栈",
                        "UP  屏幕坐标=(${event.rawX}, ${event.rawY}) | " +
                                "时长=${it.durationMs}ms | " +
                                "MOVE次数=${it.moveCount} | " +
                                "位移=${String.format("%.1f", it.distance)}px | " +
                                "是否移动=$moved | " +
                                "疑似脚本=${TouchBehaviorAnalyzer.isSuspicious(it)}"
                    )
                }
            }
            // MOVE 不打印（太频繁），已由 moveCount 累计，UP 时统一汇总
        }

        // 真正的拦截逻辑不变
        return session != null && TouchBehaviorAnalyzer.isSuspicious(session)
    }
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
        binding.btnParse.setOnClickListener{
            val decrypt = BuildConfig.GN_EE_TK.decrypt()
            val de = decrypt.restoreAllIllusion()
            val encrypt = "8930d^I^LOVE^YOU^95adcbf229dcd022298a^I^LOVE^YOU^67b273b".encrypt()
            Log.e("解密字符串", "decrypt=: "+ decrypt)
            Log.e("解密字符串", "de=: "+ de)
            Log.e("解密字符串", "encrypt=: "+ encrypt)
        }
        binding.btnPlaySaved.setOnClickListener{
            // 测试「不打开 MusicActivity 也能播放已保存歌单」：直接调用 MusicPlayer.playSaved
            // 注意：playSaved 受「提醒总开关」控制，总开关关闭时返回 false（不播）
            val ok = MusicPlayer.playSaved("测试显示用的文案")
            val msg = if (ok) {
                "已开始后台播放歌单"
            } else if (MusicStore.isBgmOn()) {
                "暂无可播放歌单，请先到「音乐播放」添加歌曲"
            } else {
                "提醒总开关未开启，无法后台播放"
            }
            AliveUtils.toast(msg = msg)
        }
        binding.fab.setOnClickListener {
//            AliveUtils.easyPermission(this@MainActivity)
            //OverlayLog.show()
            Thread {
                //throw RuntimeException("这是一个后台线程异常测试")
            }.start()


            val buildTimeMillis: Long = BuildConfig.BUILD_TIME
            // 如果要格式化输出：
            val date = java.util.Date(buildTimeMillis)
            val formatted = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(date)

            println("构建时间戳: $appBuildTime")
            println("构建时间: $formatted")
           //LockScreenActivity.openLockScreenActivity()

            //ActivityUtils.showVideoDialog(this, "https://gitlab.com/mytiper/wechat/-/raw/master/public/unlock.mp4")
            NumberPickerDialog.showDefault(context = this)





            //val webFragment = WebDialogFragment.newInstance("https://pay.ldxp.cn/shop/R24YFBFV/jtfovn")
           //webFragment.show(supportFragmentManager, "web_dialog")
            //webFragment.toggleDesktopMode()

            ActivityUtils.showKaWangDialog(activity = this,
                xiaopu = "https://pay.ldxp.cn/shop/R24YFBFV/jtfovn",
                weifk = "",
                isDan = false)


        }

        //② 局部 第二道：关键按钮做局部 onTouch 精确拦截（与全局叠加，双保险）
        binding.btnZan.setOnTouchListener(
            SafeTouchListener { v, event ->
                // 能走到这里说明已被判定为真人触摸、SafeTouchListener 已放行。
                // real 返回 false = 不消费事件，让 onClick 正常触发。
                // 若原来有 onTouch 逻辑，就把原逻辑写在这里。
                traceTouch(event)
                false
            }
        )

        //③ 兜底 第三道：onClick 兜底。无障碍 performAction(16) 不产生 MotionEvent，
        // 两道触摸拦截都拦不到它，只能在这里用「当前栈」判定。
        binding.btnZan.setOnClickListener{

            val frames = Throwable().stackTrace
                .joinToString("\n") { it.className + "." + it.methodName }
            //Log.d("调用栈", "stack:\n$frames")
            if (ScriptTouchDetector.isEvilTraceNow()) {
                AliveUtils.toast(msg = "疑似无障碍/脚本点击，拒绝执行")
                // 疑似无障碍/脚本点击，拒绝执行
                return@setOnClickListener
            }
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
            // App 启动时切到 UiAutomation（免开无障碍，需 Shizuku）
            // 直接在主线程调用，不会卡
            XpqAcc.showEngineModeDialog(this, SelectToSpeakService())
            if (true)return@setOnClickListener
            if (XpqAcc.isUiAutomationOccupied()) {
                AliveUtils.toast(msg = "检测到 UiAutomation 已被其它 App 占用")
            }
            XpqAcc.connectUiAutomation(
                onLog = { Log.d("XpqAcc", it) },
                onResult = { success, reason ->
                    if (success) {
                        // 连接成功后立刻验证
                        val root = XpqAcc.rootInActiveWindow()
                        AliveUtils.toast(msg = "连接成功，取根节点=${root != null}，isConnected=${XpqAcc.isConnected}")
  
                    }else{
                        AliveUtils.toast(msg = "失败"+reason)
                    }
                },
                activity = this,
                SelectToSpeakService()
            )



            //
            //show_AC_Warn_Dialog(this@MainActivity,false )
            //公众号ID
            //openWeChatToFollowInterface(getWCField[6].first.restoreAllIllusion())
        }
        binding.btnAddFriend.setOnClickListener{
            setShowClickIndicator(true)
            val ok = accessibilityService?.clickByText("赞赏",true)
            AliveUtils.toast(msg = "clickByText=$ok")
            Thread {

            }.start()

            //好友微信号
            //openWeChatToFollowInterface(getWCField[6].second.restoreAllIllusion())
            //openAccessibilitySetting()
            // 使用 FragmentManager 来显示 BottomSheetDialogFragment
            //val sheet = SensitiveNotificationBottomSheet()
            //sheet.show(supportFragmentManager, SensitiveNotificationBottomSheet.TAG)
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
                FabMenuItem("应用保活", com.android.accessibility.ext.R.drawable.icon3_xpq) {
                    AliveUtils.openAliveActivity(true,
                        false,
                        NotificationListenerServiceImp::class.java,
                         true
                    )
                },
                FabMenuItem("视频播放", com.android.accessibility.ext.R.drawable.scale_xpq) {
                    var url = "https://v.douyin.com/zkSF9GvODpk/ i@p.dN 04/03 OKw:/"
                    url = "https://v.kuaishou.com/nP273bPw"
                    ActivityUtils.showWebViewDialog(activity = this@MainActivity,url)


                    //ActivityUtils.showVideoDialog(this@MainActivity, "https://gitlab.com/mytiper/wechat/-/raw/master/public/unlock.mp4")
                },
                FabMenuItem("解锁方案", com.android.accessibility.ext.R.drawable.move_xpq) {
                    NumberPickerDialog.showDefault(context = this@MainActivity)
                },
                FabMenuItem("输入数字", com.android.accessibility.ext.R.drawable.move_xpq) {
                   
                    NumberInputSDK.showSecondsInputDialog(context = this@MainActivity)
                },
                FabMenuItem("网络测试", com.android.accessibility.ext.R.drawable.move_xpq) {
                    CoroutineScope(Dispatchers.Main).launch {
                        if (intervalIsDuan()){
                            AliveUtils.toast(msg = "间隔太短")
                            return@launch  // 防抖
                        }
                        val result = NetworkHelperFullSmart.checkNetworkAndGetTimeSmart(this@MainActivity)

                        when (result.status) {
                            NetworkHelperFullSmart.NetStatus.INTERNET_OK -> {
                                AliveUtils.toast(msg = "时间: ${result.time}")
                            }
                            else -> {
                                AliveUtils.toast(msg = "网络异常: ${result.status}")
                                Log.e("网络", "网络异常: ${result.status}")
                            }
                        }
                    }

                },
                FabMenuItem("充值会员", com.android.accessibility.ext.R.drawable.minimize_xpq) {
                    ActivityUtils.showKaWangDialog(activity = this@MainActivity,
                        xiaopu = "https://pay.ldxp.cn/shop/R24YFBFV/jtfovn",
                        weifk = "",
                        isDan = false)
                },
                FabMenuItem("获取坐标", com.android.accessibility.ext.R.drawable.minimize_xpq) {
                JieSuoUtils.showDialogZuobiao()
                },
                FabMenuItem("隐私政策", com.android.accessibility.ext.R.drawable.minimize_xpq) {
                    //AppInfoUtil.privacy_GuoNei_SJ(this,"","")
                    AppInfoUtil.showAccessibilityAgreement(this)
                },
                FabMenuItem("验证码填充", com.android.accessibility.ext.R.drawable.minimize_xpq) {
                    LoginDialog(this,true) { phone, code ->
                        //调登录接口
                    }.show()

                },
                FabMenuItem("音乐播放", com.android.accessibility.ext.R.drawable.ic_music_xpq) {
                    MusicPlayer.openMusic(this@MainActivity)
                },
                FabMenuItem("检查更新", com.android.accessibility.ext.R.drawable.minimize_xpq) {
                    checkForUpdate()
                    //OverlayLog.show()

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

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val result = UpdateManager.checkForUpdate(force = true)

            when (result) {
                is UpdateRepository.CheckResult.NewVersion -> {
                    UpdateDialogHelper.showUpdateAvailableDialog(
                        context = this@MainActivity,
                        version = result.state.latestVersion,
                        releaseNotes = result.state.notes,
                        apkUrl = result.state.apkUrl,
                        apkSize = result.state.apkSize,
                        onConfirm = { startDownload(result.state) }
                    )
                }

                is UpdateRepository.CheckResult.UpToDate -> {
                    UpdateDialogHelper.showAlreadyLatestDialog(this@MainActivity)
                }

                is UpdateRepository.CheckResult.Failed -> {
                    UpdateDialogHelper.showCheckFailedDialog(
                        this@MainActivity,
                        onConfirm = { openGitHubPage() }
                    )
                }

                is UpdateRepository.CheckResult.RateLimited -> {
                    UpdateDialogHelper.showRateLimitedDialog(
                        this@MainActivity,
                        onConfirm = { openGitHubPage() }
                    )
                }

                is UpdateRepository.CheckResult.NoApk -> {
                    UpdateDialogHelper.showNoApkDialog(
                        this@MainActivity,
                        onConfirm = { openGitHubPage() }
                    )
                }

                UpdateRepository.CheckResult.Skipped -> {
                    // 缓存未过期，跳过检查
                }
            }
        }
    }

    private fun startDownload(state: UpdateState) {
        if (!UpdateManager.canInstall(this@MainActivity)) {
            UpdateManager.gotoUnknownSourceSetting(this@MainActivity)
            return
        }

        UpdateManager.downloadUpdate(this@MainActivity, state.latestVersion, state.apkUrl, state.apkSize)

        val (dialog, job) = UpdateDialogHelper.showDownloadProgressDialog(this@MainActivity)

        // 等待对话框关闭后检查 APK 并安装
        lifecycleScope.launch {
            while (dialog.isShowing) {
                delay(200)
            }
            val apkFile = ApkInstaller.apkFile(this@MainActivity, state.latestVersion)
            if (ApkInstaller.isDownloaded(apkFile, state.apkSize)) {
                UpdateManager.installUpdate(this@MainActivity, state.latestVersion)
            }
        }
    }
    /**
     * 打开 GitHub Releases 页面。
     */
    private fun openGitHubPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse(UpdateManager.getReleasesPageUrl())))
        } catch (_: Exception) {
        }
    }
}