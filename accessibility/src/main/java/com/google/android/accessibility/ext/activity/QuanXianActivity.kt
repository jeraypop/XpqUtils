package com.google.android.accessibility.ext.activity

import android.Manifest
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.ActivityQuanXianBinding
import com.google.android.accessibility.ext.activity.AliveFGService.Companion.fgs_ison
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.MMKVConst
import com.google.android.accessibility.ext.utils.MMKVUtil
import com.google.android.accessibility.ext.utils.NotificationUtil
import com.google.android.accessibility.ext.utils.NotificationUtil.isNotificationEnabled
import com.google.android.accessibility.ext.utils.SPUtils
import com.google.android.accessibility.ext.utils.Utilshezhi
import com.google.android.accessibility.notification.ClearNotificationListenerServiceImp
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.hjq.permissions.permission.PermissionLists
import java.util.Locale

class QuanXianActivity : AppCompatActivity() {

    private var devicePolicyManager: DevicePolicyManager? = null
    private var packageManager: PackageManager? = null
    private var powerManager: PowerManager? = null
    private lateinit var binding: ActivityQuanXianBinding
    private var drawableYes: Drawable? = null
    private var drawableNo: Drawable? = null

    private var serviceClass: Class<out NotificationListenerService>? = null
    private  var showReadBar = false
    private var readnotificationbar:Switch? = null

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
        binding = ActivityQuanXianBinding.inflate(layoutInflater)
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

        drawableYes = ContextCompat.getDrawable(this, R.drawable.ic_open)
        drawableNo = ContextCompat.getDrawable(this, R.drawable.ic_close)
        updateUI()
        // 获取传递的 Class 对象
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            serviceClass = intent.getSerializableExtra(MMKVConst.NOTIFICATION_SERVICE_CLASS, Class::class.java) as? Class<out NotificationListenerService>
        } else {
            @Suppress("DEPRECATION")
            serviceClass = intent.getSerializableExtra(MMKVConst.NOTIFICATION_SERVICE_CLASS) as? Class<out NotificationListenerService>
        }

        showReadBar = intent.getBooleanExtra(MMKVConst.SHOW_READ_NOTIFICATION, false)


        //====================按钮监测===============================================
        //电池优化
        binding.buttonPowerPermission.setOnClickListener {
            //  打开电池优化的界面，让用户设置
        /*    if (powerManager!!.isIgnoringBatteryOptimizations(packageName)) {
                AliveUtils.toast(applicationContext, "忽略电池优化权限已开启")
            } else {
                val intentBatteryIgnore = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                if (intentBatteryIgnore.resolveActivity(packageManager!!) != null) {
                    startActivity(intentBatteryIgnore)
                } else {
                    AliveUtils.toast(applicationContext, "授权窗口打开失败，请手动打开")
                }
            }*/

            //===
            val easyPermission = AliveUtils.easyRequestPermission(this@QuanXianActivity, PermissionLists.getRequestIgnoreBatteryOptimizationsPermission(),"忽略电池优化")
            if (easyPermission) {
                binding.imagePowerPermission.setImageDrawable(drawableYes)
            } else {
                binding.imagePowerPermission.setImageDrawable(drawableNo)
            }
            //===
        }

        //前台服务
        binding.buttonQiantaifuwuPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) {
                // 检查Android14前台服务权限
                val permissionCheck = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
                if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                    return@setOnClickListener
                }
            }

            //===
          
            if (isNotificationEnabled()){
                //设置通知标题内容对话框
                showCustomizeDialog()
            }else{
                val easyPermission = AliveUtils.easyRequestPermission(this@QuanXianActivity, PermissionLists.getPostNotificationsPermission(),"发送通知")
                if (easyPermission) {
                    showCustomizeDialog()
                }
            }
            //===
        }
        binding.trReadNotification.visibility = if (showReadBar){
            View.VISIBLE
        }else{
            View.GONE
        }
        //读取通知栏权限
        binding.buttonReadNotifiPermission.setOnClickListener {
            //  打开让用户设置
            if (NotificationUtil.isNotificationListenersEnabled()) {
                AliveUtils.toast(applicationContext, "权限已开启")
            } else {

                AlertDialog.Builder(this)
                    .setMessage("后台跳转,提醒功能,需要授权")
                    .setPositiveButton("去开启") { _, _ ->
                        NotificationUtil.gotoNotificationAccessSetting()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        AliveUtils.toast(applicationContext, "取消")
                    }
                    .show()
            }
        }

        //设备管理员
        binding.buttonGuanliyuanPermission.setOnClickListener {
            val firstInstallTime = AliveUtils.getFirstInstallTime(applicationContext)
            val yuDay = 30 - (System.currentTimeMillis() - firstInstallTime!!) / (24 * 60 * 60 * 1000L)
            val msg: String = if (0 <= yuDay && yuDay <= 30) {
                String.format(Locale.ROOT, getString(R.string.quanxianguanliyuan), yuDay)
            } else {
                getString(R.string.quanxianguanliyuan1)
            }

            val normalDialog = AlertDialog.Builder(this@QuanXianActivity)
            //                normalDialog.setIcon(R.drawable.ic_float_app);
            normalDialog.setTitle(getString(R.string.wenxintixing))
            normalDialog.setMessage(msg)
            normalDialog.setPositiveButton(getString(R.string.nimbleisopen)) { dialog, which ->
                // 0<=yuDay && yuDay<=30
                if (0<=yuDay && yuDay<=30) {
                    AliveUtils.toast(applicationContext, "" + yuDay)
                } else {
              /*      //
                    val compMyDeviceAdmin = ComponentName(applicationContext, MyDeviceAdminReceiver::class.java)
                    if (devicePolicyManager!!.isAdminActive(compMyDeviceAdmin)) {
                        AliveUtils.toast(applicationContext, getString(R.string.quanxian11))
                    } else {
                        val intentDeviceAdmin = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        intentDeviceAdmin.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compMyDeviceAdmin)
                        if (intentDeviceAdmin.resolveActivity(packageManager!!) != null) {
                            startActivity(intentDeviceAdmin)
                        } else {
                            AliveUtils.toast(applicationContext, getString(R.string.quanxian30))
                        }
                    }*/

                    //===
                    val easyPermission = AliveUtils.easyRequestPermission(this@QuanXianActivity, PermissionLists.getBindDeviceAdminPermission(MyDeviceAdminReceiver::class.java),"设备管理员")
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
                val devAdminReceiver = ComponentName(applicationContext, MyDeviceAdminReceiver::class.java)
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
            // 显示
            normalDialog.show()
        }

        //0像素
        binding.button0xiangsuPermission.setOnClickListener {
            //===
            val keepAliveByFloatingWindow = !AliveUtils.getKeepAliveByFloatingWindow()
            AliveUtils.setKeepAliveByFloatingWindow(keepAliveByFloatingWindow)
            AliveUtils.requestUpdateKeepAliveByFloatingWindow(keepAliveByFloatingWindow)
            if (AliveUtils.getKeepAliveByFloatingWindow()) {
                binding.image0xiangsuPermission.setImageDrawable(drawableYes)
            } else {
                binding.image0xiangsuPermission.setImageDrawable(drawableNo)
            }
            AliveUtils.toast(applicationContext, if (keepAliveByFloatingWindow) getString(R.string.quanxian11) else getString(R.string.quanxian13))
            //===
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
            val easyPermission = AliveUtils.easyRequestPermission(this@QuanXianActivity, PermissionLists.getSystemAlertWindowPermission(),"悬浮窗")
            if (easyPermission) {
                binding.imageFloatPermission.setImageDrawable(drawableYes)
            } else {
                binding.imageFloatPermission.setImageDrawable(drawableNo)
            }
            //===
        }

        //===自启动
        binding.buttonPowerPermission2.setOnClickListener {
            //自启动管理界面
            Utilshezhi.startToAutoStartSetting(this@QuanXianActivity)
        }
        //==加锁免清理
        binding.buttonPowerPermission3.setOnClickListener {
            //打开最近任务列表
            if (SelectToSpeakServiceAbstract.instance == null) {
                AliveUtils.toast(applicationContext, getString(R.string.lockapp))
            } else {
                AliveUtils.toast(applicationContext, getString(R.string.quanxian31))
                SelectToSpeakServiceAbstract.instance!!.performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
        //==设置
        binding.buttonPowerPermission4.setOnClickListener {
            //打开设置
            Utilshezhi.gotoPermission(this@QuanXianActivity)
        }
    }

    private fun updateUI() {
        //图形开关监测

        //电池优化
        val packageName = this@QuanXianActivity.packageName

        if (powerManager!!.isIgnoringBatteryOptimizations(packageName)) {
            binding.imagePowerPermission.setImageDrawable(drawableYes)
        } else {
            binding.imagePowerPermission.setImageDrawable(drawableNo)
        }

        //前台服务
        if (fgs_ison) {
            binding.imageQiantaifuwuPermission.setImageDrawable(drawableYes)
        } else {
            binding.imageQiantaifuwuPermission.setImageDrawable(drawableNo)
        }

        //读取通知栏
        if (NotificationUtil.isNotificationListenersEnabled()) {
            binding.imageReadNotifiPermission.setImageDrawable(drawableYes)
        } else {
            binding.imageReadNotifiPermission.setImageDrawable(drawableNo)
        }

        //设备管理员
        if (devicePolicyManager!!.isAdminActive(ComponentName(applicationContext, MyDeviceAdminReceiver::class.java))) {
            binding.imageGuanliyuanPermission.setImageDrawable(drawableYes)
        } else {
            binding.imageGuanliyuanPermission.setImageDrawable(drawableNo)
        }

        //0像素保活
        if (AliveUtils.getKeepAliveByFloatingWindow()) {
            binding.image0xiangsuPermission.setImageDrawable(drawableYes)
        } else {
            binding.image0xiangsuPermission.setImageDrawable(drawableNo)
        }

        //悬浮窗
        if (Settings.canDrawOverlays(this)) {
            binding.imageFloatPermission.setImageDrawable(drawableYes)
        } else {
            binding.imageFloatPermission.setImageDrawable(drawableNo)
        }

        readnotificationbar?.isChecked = NotificationUtil.isNotificationListenersEnabled()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
    }

    private fun showCustomizeDialog() {
        /* @setView 装入自定义View ==> R.layout.dialog_customize
         */
        val customizeDialog = AlertDialog.Builder(this@QuanXianActivity)
        val dialogView = LayoutInflater.from(this@QuanXianActivity)
            .inflate(R.layout.forgroundservice_dialog_view, null)
        // 获取EditView中的输入内容
        val editTexttitle = dialogView.findViewById<EditText>(R.id.dialog_edit_title)
        val editTextcontent = dialogView.findViewById<EditText>(R.id.dialog_edit_content)

        editTexttitle.setText(MMKVUtil.get(MMKVConst.FORGROUNDSERVICETITLE, ""))
        editTextcontent.setText(MMKVUtil.get(MMKVConst.FORGROUNDSERVICECONTENT, ""))
        val llreadnotificationbar = dialogView.findViewById<LinearLayout>(R.id.llreadnotificationbar)
        //自动通知栏保活开关
        val autobaohuo = dialogView.findViewById<Switch>(R.id.autobaohuo)
        autobaohuo.setOnClickListener {
            val isChecked = autobaohuo.isChecked
            SPUtils.putBoolean(MMKVConst.AUTOBAOHUOISON, isChecked)
            AliveUtils.setKeepAliveByNotification(isChecked)
        }

        autobaohuo.isChecked = AliveUtils.getKeepAliveByNotification()
        //自动清除通知栏保活的通知
        val clearautobaohuo = dialogView.findViewById<Switch>(R.id.clearautobaohuo)
        clearautobaohuo.setOnClickListener {
            val isChecked = clearautobaohuo.isChecked
            AliveUtils.setAC_AliveNotification(isChecked)
            if (isChecked){
                llreadnotificationbar.visibility = View.VISIBLE
            }else{
                llreadnotificationbar.visibility = View.GONE
            }
        }

        clearautobaohuo.isChecked = AliveUtils.getAC_AliveNotification()
        //开启读取通知栏权限
        readnotificationbar = dialogView.findViewById<Switch>(R.id.readnotificationbar)
        readnotificationbar?.setOnClickListener {
            if (serviceClass!= null){
                AliveUtils.openNotificationListener(this, serviceClass!!)
            }else{
                if (isServiceDeclared(applicationContext, ClearNotificationListenerServiceImp::class.java)) {
                    AliveUtils.openNotificationListener(this, ClearNotificationListenerServiceImp::class.java)
                }else{
                    NotificationUtil.gotoNotificationAccessSetting()
                }
            }
        }

        readnotificationbar?.isChecked = NotificationUtil.isNotificationListenersEnabled()

        if (clearautobaohuo.isChecked){
            llreadnotificationbar.visibility = View.VISIBLE
        }else{
            llreadnotificationbar.visibility = View.GONE
        }

        customizeDialog.setTitle(getString(R.string.quanxian9))
        customizeDialog.setView(dialogView)
        //确定按钮
        customizeDialog.setPositiveButton(getString(R.string.ok)) { dialog, which ->
            val title = editTexttitle.text.toString().trim { it <= ' ' }
            val content = editTextcontent.text.toString().trim { it <= ' ' }

            if (TextUtils.isEmpty(title) || TextUtils.isEmpty(content)) {
                MMKVUtil.put(MMKVConst.FORGROUNDSERVICETITLE, getString(R.string.wendingrun2))
                MMKVUtil.put(MMKVConst.FORGROUNDSERVICECONTENT, getString(R.string.wendingrun4))
            } else {
                MMKVUtil.put(MMKVConst.FORGROUNDSERVICETITLE, title)
                MMKVUtil.put(MMKVConst.FORGROUNDSERVICECONTENT, content)
            }

            AliveUtils.toast(applicationContext, MMKVUtil.get(MMKVConst.FORGROUNDSERVICETITLE, getString(R.string.wendingrun2)))
            //===
            //启动服务
            AliveUtils.startFGAlive(enable = true)
            //===
            AliveUtils.setKeepAliveByNotification(true)
            binding.imageQiantaifuwuPermission.setImageDrawable(drawableYes)
        }
        //取消按钮
        customizeDialog.setNegativeButton(getString(R.string.quanxian14)) { dialog, which ->
            //==
            if (!fgs_ison) {
                AliveUtils.toast(applicationContext, getString(R.string.quanxian12))
            } else {
                AliveUtils.toast(applicationContext, getString(R.string.quanxian13))
                //停止服务 这将触发服务的 onDestroy() 方法，释放资源并关闭前台通知
                AliveUtils.startFGAlive(enable = false)
            }
            //==
            AliveUtils.setKeepAliveByNotification(false)
            binding.imageQiantaifuwuPermission.setImageDrawable(drawableNo)
        }
        customizeDialog.show()
    }

    fun isServiceDeclared(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val pm = context.packageManager
            val componentName = ComponentName(context, serviceClass)
            val info = pm.getServiceInfo(componentName, PackageManager.GET_META_DATA)
            info != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }


}