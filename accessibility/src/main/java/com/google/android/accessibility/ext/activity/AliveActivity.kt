package com.google.android.accessibility.ext.activity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import android.view.View
import android.widget.TableRow
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.ActivityAliveXpqBinding

import com.google.android.accessibility.ext.activity.AliveFGService.Companion.fgs_ison
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.AliveUtils.isServiceDeclared
import com.google.android.accessibility.ext.utils.AliveUtils.shouxianzhi
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.NotificationUtilXpq
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.isNotificationListenerEnabled
import com.google.android.accessibility.notification.ClearNotificationListenerServiceImp
import com.hjq.permissions.permission.PermissionLists

class AliveActivity : XpqBaseActivity<ActivityAliveXpqBinding>(
    bindingInflater = ActivityAliveXpqBinding::inflate
) {

    private var devicePolicyManager: DevicePolicyManager? = null
    private var packageManager: PackageManager? = null
    private var powerManager: PowerManager? = null
    private lateinit var binding: ActivityAliveXpqBinding
    private var drawableYes: Drawable? = null
    private var drawableNo: Drawable? = null

    private var serviceClass: Class<out NotificationListenerService>? = null
    private  var showReadBar = false
    private  var showTskHide = false

   

    @RequiresApi(api = Build.VERSION_CODES.M)
    override fun onResume() {
        super.onResume()
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    override fun onRestart() {
        super.onRestart()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAliveXpqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageManager = packageManager
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        //当点击当前 Activity 以外的区域（例如弹出的对话框外部）时，自动关闭（finish）该 Activity
        setFinishOnTouchOutside(true)

        //教程指导
        binding.textInstructions.setOnClickListener {
            val intenti2 = Intent()
            intenti2.action = "android.intent.action.VIEW"
            val content_url2 = Uri.parse("https://mp.weixin.qq.com/s/umGL41SgEapebNA8Pz1Tjw")
            intenti2.data = content_url2
            startActivity(intenti2)
        }

        drawableYes = ContextCompat.getDrawable(this, R.drawable.ic_open_xpq)
        drawableNo = ContextCompat.getDrawable(this, R.drawable.ic_close_xpq)

        // 获取传递的 Class 对象
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            serviceClass = intent.getSerializableExtra(MMKVConst.NOTIFICATION_SERVICE_CLASS, Class::class.java) as? Class<out NotificationListenerService>
        } else {
            @Suppress("DEPRECATION")
            serviceClass = intent.getSerializableExtra(MMKVConst.NOTIFICATION_SERVICE_CLASS) as? Class<out NotificationListenerService>
        }

        showReadBar = intent.getBooleanExtra(MMKVConst.SHOW_READ_NOTIFICATION, false)
        showTskHide = intent.getBooleanExtra(MMKVConst.SHOW_TASK_HIDE, false)
        updateUI()
        //====================按钮监测===============================================
        //电池优化
        binding.buttonPowerPermission.setOnClickListener {
            //  打开电池优化的界面，让用户设置
        /*    if (powerManager!!.isIgnoringBatteryOptimizations(packageName)) {
                AliveUtils.toast(applicationContext, "忽略电池优化")
            } else {
                val intentBatteryIgnore = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                if (intentBatteryIgnore.resolveActivity(packageManager!!) != null) {
                    startActivity(intentBatteryIgnore)
                } else {
                    AliveUtils.toast(applicationContext, "授权窗口打开失败，请手动打开")
                }
            }*/

            //===
            val easyPermission = AliveUtils.easyRequestPermission(this@AliveActivity, PermissionLists.getRequestIgnoreBatteryOptimizationsPermission(),"忽略电池优化")
            if (easyPermission) {
                binding.imagePowerPermission.setImageDrawable(drawableYes)
            } else {
                binding.imagePowerPermission.setImageDrawable(drawableNo)
            }
            //===
        }

        //前台服务
        binding.buttonQiantaifuwuPermission.setOnClickListener {
            AliveUtils.setForgrountDialog(
                this@AliveActivity,
                appContext,
                serviceClass,
                binding.imageQiantaifuwuPermission)
    /*        if (Build.VERSION.SDK_INT >= 34) {
                // 检查Android14前台服务权限
                val permissionCheck = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
                if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                    return@setOnClickListener
                }
            }

            //===
            if (isNotificationEnabled()){
                //设置通知标题内容对话框
                //showCustomizeDialog()
                AliveUtils.showForgrountDialog(
                    this@AliveActivity,
                    appContext,
                    serviceClass,
                    binding.imageQiantaifuwuPermission
                    )
            }else{
                val easyPermission = AliveUtils.easyRequestPermission(this@AliveActivity, PermissionLists.getPostNotificationsPermission(),"发送通知")
                if (easyPermission) {
                    AliveUtils.showForgrountDialog(
                        this@AliveActivity,
                        appContext,
                        serviceClass,
                        binding.imageQiantaifuwuPermission
                    )
                }
            }*/
            //===
        }

        //读取通知栏权限
        binding.trReadNotification.visibility = if (showReadBar){
            View.VISIBLE
        }else{
            View.GONE
        }
        binding.buttonReadNotifiPermission.setOnClickListener {
            //  打开让用户设置
            if (NotificationUtilXpq.isNotificationListenersEnabled()) {
                AliveUtils.toast(applicationContext, getString(R.string.qxykqxpq))
            } else {

                AlertDialog.Builder(this@AliveActivity)
                    .setMessage(getString(R.string.dqtzllxpq))
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->
                        NotificationUtilXpq.gotoNotificationAccessSetting()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                        AliveUtils.toast(applicationContext, getString(R.string.cancel))
                    }
                    .setNeutralButton(getString(R.string.sxzxpq)){_, _ ->
                        shouxianzhi()
                    }
                    .show()
            }
        }

        //后台隐藏
        binding.trRecentTaskHide.visibility = if (showTskHide){
            View.VISIBLE
        }else{
            View.GONE
        }
        binding.buttonRecentTaskHidePermission.setOnClickListener {
            AliveUtils.backendTaskHide()
            if (AliveUtils.getKeepAliveByTaskHide()) {
                binding.imageRecentTaskHidePermission.setImageDrawable(drawableYes)
            } else {
                binding.imageRecentTaskHidePermission.setImageDrawable(drawableNo)
            }

        }

        //设备管理员
        binding.buttonGuanliyuanPermission.setOnClickListener {
            AliveUtils.openAdmin(
                null,
                this@AliveActivity,
                appContext,
                binding.
                imageGuanliyuanPermission
            )
     /*       val firstInstallTime = AliveUtils.getFirstInstallTime(applicationContext)
            val yuDay = 30 - (System.currentTimeMillis() - firstInstallTime!!) / (24 * 60 * 60 * 1000L)
            val msg: String = if (0 <= yuDay && yuDay <= 30) {
                String.format(Locale.ROOT, getString(R.string.quanxianguanliyuan), yuDay)
            } else {
                getString(R.string.quanxianguanliyuan1)
            }

            val normalDialog = AlertDialog.Builder(this@AliveActivity)
            //                normalDialog.setIcon(R.drawable.ic_float_app);
            normalDialog.setTitle(getString(R.string.wenxintixing))
            normalDialog.setMessage(msg)
            normalDialog.setPositiveButton(getString(R.string.nimbleisopen)) { dialog, which ->
                // 0<=yuDay && yuDay<=30
                if (0<=yuDay && yuDay<=30) {
                    AliveUtils.toast(applicationContext, "" + yuDay)
                } else {
                    //
//                    val compMyDeviceAdmin = ComponentName(applicationContext, MyDeviceAdminReceiverXpq::class.java)
//                    if (devicePolicyManager!!.isAdminActive(compMyDeviceAdmin)) {
//                        AliveUtils.toast(applicationContext, getString(R.string.quanxian11))
//                    } else {
//                        val intentDeviceAdmin = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
//                        intentDeviceAdmin.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compMyDeviceAdmin)
//                        if (intentDeviceAdmin.resolveActivity(packageManager!!) != null) {
//                            startActivity(intentDeviceAdmin)
//                        } else {
//                            AliveUtils.toast(applicationContext, getString(R.string.quanxian30))
//                        }
//                    }

                    //===
                    val easyPermission = AliveUtils.easyRequestPermission(this@AliveActivity, PermissionLists.getBindDeviceAdminPermission(MyDeviceAdminReceiverXpq::class.java),"设备管理员")
                    if (easyPermission) {
                        binding.imageGuanliyuanPermission.setImageDrawable(drawableYes)
                    } else {
                        binding.imageGuanliyuanPermission.setImageDrawable(drawableNo)
                    }
                    //===
                }
            }
            normalDialog.setNegativeButton(getString(R.string.cancel)) { dialog, which ->
                //...To-do
                val devAdminReceiver = ComponentName(applicationContext, MyDeviceAdminReceiverXpq::class.java)
                val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

                if (dpm.isAdminActive(devAdminReceiver)) {
                    dpm.removeActiveAdmin(devAdminReceiver)
                    AliveUtils.toast(applicationContext, getString(R.string.quanxian13))
                    binding.imageGuanliyuanPermission.setImageDrawable(drawableNo)
                } else {
                    AliveUtils.toast(applicationContext, getString(R.string.quanxian12))
                    binding.imageGuanliyuanPermission.setImageDrawable(drawableNo)
                }


            }

            normalDialog.setNeutralButton(getString(R.string.sxzxpq)) { dialog, which ->
                //...To-do
                shouxianzhi()


            }
            // 显示
            normalDialog.show()*/
        }



        //悬浮窗
        binding.buttonFloatPermission.setOnClickListener {
        /*    val intentAlertWindow = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            if (intentAlertWindow.resolveActivity(packageManager!!) != null) {
                startActivity(intentAlertWindow)
            } else {
                AliveUtils.toast(applicationContext, "授权窗口打开失败，请手动打开")
            }*/

            //===
            val easyPermission = AliveUtils.easyRequestPermission(this@AliveActivity, PermissionLists.getSystemAlertWindowPermission(),"悬浮窗")
            if (easyPermission) {
                binding.imageFloatPermission.setImageDrawable(drawableYes)
            } else {
                binding.imageFloatPermission.setImageDrawable(drawableNo)
            }
            //===
        }

        //0像素
        binding.button0xiangsuPermission.setOnClickListener {

            val isOpen = AliveUtils.pixl0Alive()
            if (isOpen && AliveUtils.getKeepAliveByFloatingWindow()) {
                binding.image0xiangsuPermission.setImageDrawable(drawableYes)
            } else {
                binding.image0xiangsuPermission.setImageDrawable(drawableNo)
            }

        }

        //===自启动
        binding.buttonPowerPermission2.setOnClickListener {
            AliveUtils.showCheckDialog(this@AliveActivity,R.string.yxzqdxpq,R.drawable.autostart_xpq,R.string.quanxian6,MMKVConst.BTN_AUTOSTART)
        }
        //==加锁免清理
        binding.buttonPowerPermission3.setOnClickListener {
            AliveUtils.showCheckDialog(this@AliveActivity,R.string.lockrencentxpq,R.drawable.lockalive_xpq,R.string.quanxian7,MMKVConst.BTN_RECENTS)

        }
        //==设置
        binding.buttonPowerPermission4.setOnClickListener {

            AliveUtils.showCheckDialog(this@AliveActivity,R.string.httcxpq,R.drawable.backgroundshow_xpq,R.string.checkqxxpq,MMKVConst.BTN_PERMISSION)


        }
    }

    override fun initView_Xpq() {
        requireBinding().trRecentTaskHide.setOnClickListener{

        }
        val row = findViewById<TableRow>(R.id.tr_recentTaskHide)
    }

    override fun initData_Xpq() {

    }


    private fun updateUI() {
        //图形开关监测

        //电池优化
        val packageName = this@AliveActivity.packageName

        if (powerManager!!.isIgnoringBatteryOptimizations(packageName)) {
            binding.imagePowerPermission.setImageDrawable(drawableYes)
        } else {
            binding.imagePowerPermission.setImageDrawable(drawableNo)
        }

        //前台服务
        if (fgs_ison && NotificationUtilXpq.isNotificationEnabled()) {
            binding.imageQiantaifuwuPermission.setImageDrawable(drawableYes)
        } else {
            binding.imageQiantaifuwuPermission.setImageDrawable(drawableNo)
        }

        //读取通知栏
//        if (NotificationUtil.isNotificationListenersEnabled()) {
//            binding.imageReadNotifiPermission.setImageDrawable(drawableYes)
//        } else {
//            binding.imageReadNotifiPermission.setImageDrawable(drawableNo)
//        }

        val b = if (serviceClass!= null){
            isNotificationListenerEnabled(appContext, serviceClass!!)
        }else{
            if (isServiceDeclared(appContext, ClearNotificationListenerServiceImp::class.java)) {
                isNotificationListenerEnabled(appContext, ClearNotificationListenerServiceImp::class.java)
            }else{
                NotificationUtilXpq.isNotificationListenersEnabled()
            }
        }
        if (b) {
            binding.imageReadNotifiPermission.setImageDrawable(drawableYes)
        } else {
            binding.imageReadNotifiPermission.setImageDrawable(drawableNo)
        }

        //设备管理员
        if (devicePolicyManager!!.isAdminActive(ComponentName(applicationContext, MyDeviceAdminReceiverXpq::class.java))) {
            binding.imageGuanliyuanPermission.setImageDrawable(drawableYes)
        } else {
            binding.imageGuanliyuanPermission.setImageDrawable(drawableNo)
        }

        //0像素保活
        if (AliveUtils.show0Pixl() && AliveUtils.getKeepAliveByFloatingWindow()) {
            binding.image0xiangsuPermission.setImageDrawable(drawableYes)
        } else {
            binding.image0xiangsuPermission.setImageDrawable(drawableNo)
        }
         //后台隐藏
        if (AliveUtils.getKeepAliveByTaskHide()) {
            binding.imageRecentTaskHidePermission.setImageDrawable(drawableYes)
        } else {
            binding.imageRecentTaskHidePermission.setImageDrawable(drawableNo)
        }

        //悬浮窗
        if (AliveUtils.hasOverlayPermission(appContext)) {
            binding.imageFloatPermission.setImageDrawable(drawableYes)
        } else {
            binding.imageFloatPermission.setImageDrawable(drawableNo)
        }


    }








}

