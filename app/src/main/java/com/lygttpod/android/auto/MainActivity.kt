package com.lygttpod.android.auto



//import com.lygttpod.android.activity.result.api.observer.PermissionApi


import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.navigation.ui.AppBarConfiguration
import com.google.android.accessibility.ext.activity.QuanXianActivity
import com.google.android.accessibility.ext.donate.DonateConfig
import com.google.android.accessibility.ext.donate.Donate
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
        }

        binding.btnZan.setOnClickListener{
            Donate.init(
                this,
                DonateConfig.Builder().build()
//                DonateConfig.Builder("fkx11204qu3e298yblfpx51", R.mipmap.ic_zhifubao, R.mipmap.ic_weixin).build()
            )
        }

        binding.btnAlive.setOnClickListener{
            // 创建一个Intent，指定要启动的Activity
            val intent = Intent(this, QuanXianActivity::class.java)
            // 启动Activity
            startActivity(intent)
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