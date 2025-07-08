package com.lygttpod.android.auto



import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.accessibility.ext.isAccessibilityOpened
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
//import com.lygttpod.android.activity.result.api.observer.PermissionApi

import xpq.friend.R
import xpq.friend.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val accServiceLiveData = MutableLiveData<Boolean>()

//    private val permissionApi = PermissionApi(this)
    private var ignoreView: View? = null
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
//            keepAliveByFloatingWindow(open);

        }

        binding.fab.setOnClickListener {
            // 创建一个Intent，指定要启动的Activity
//            val intent = Intent(this, QuanXianActivity::class.java)
            // 启动Activity
//            startActivity(intent)

            AliveUtils.easyPermission(this@MainActivity)
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