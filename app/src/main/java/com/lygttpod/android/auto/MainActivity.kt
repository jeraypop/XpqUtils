package com.lygttpod.android.auto



//import com.lygttpod.android.activity.result.api.observer.PermissionApi


import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.navigation.ui.AppBarConfiguration
import com.android.accessibility.ext.BuildConfig

import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.MMKVUtil

import com.google.android.accessibility.ext.wcapi.PayConfig
import com.google.android.accessibility.ext.wcapi.decrypt
import com.google.android.accessibility.ext.wcapi.encrypt
import com.google.android.accessibility.ext.wcapi.getWCField
import com.google.android.accessibility.ext.wcapi.openDonate
import com.google.android.accessibility.ext.wcapi.openWeChatToFollowInterface
import com.google.android.accessibility.ext.wcapi.restoreAllIllusion
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.tencent.mmkv.MMKV

import xpq.friend.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val accServiceLiveData = MutableLiveData<Boolean>()

    private var windowManager: WindowManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

//        val navController = findNavController(R.id.nav_host_fragment_content_main)
//        appBarConfiguration = AppBarConfiguration(navController.graph)
//        setupActionBarWithNavController(navController, appBarConfiguration)
        windowManager = getSystemService<WindowManager>(WindowManager::class.java)
        accServiceLiveData.observe(this) { open ->

        }

        binding.fab.setOnClickListener {
//            AliveUtils.easyPermission(this@MainActivity)

            val decrypt = BuildConfig.INTENT_URL_FORMAT.decrypt()
            val de = decrypt.restoreAllIllusion()
            val encrypt = "L7763^I^LOVE^YOU^66664".encrypt()
            Log.e("解密字符串", "decrypt=: "+ decrypt)
            Log.e("解密字符串", "de=: "+ de)
            Log.e("解密字符串", "encrypt=: "+ encrypt)

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
            AliveUtils.openAliveActivity()
        }
        binding.btnAccessibility.setOnClickListener{
            AliveUtils.openAccessibility(this, SelectToSpeakService::class.java)
        }
        binding.btnGZH.setOnClickListener{
            //公众号ID

            openWeChatToFollowInterface(getWCField[6].first.restoreAllIllusion())
        }
        binding.btnAddFriend.setOnClickListener{
            //好友微信号
//            openWeChatToFollowInterface(getWCField[6].second.restoreAllIllusion())
            AliveUtils.hasOpenService(this,SelectToSpeakService::class.java)
            MMKV.initialize( this)
        }


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