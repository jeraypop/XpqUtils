package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.AppTask
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.activity.AliveActivity
import com.google.android.accessibility.ext.activity.AliveFGService
import com.google.android.accessibility.ext.activity.AliveFGService.Companion.fgs_ison
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.contentProviderAuthority
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_AUTOSTART
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_PERMISSION
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_RECENTS
import com.google.android.accessibility.ext.utils.MMKVConst.CLEARAUTOBAOHUOISON
import com.google.android.accessibility.ext.utils.MMKVConst.KEEP_ALIVE_BY_FLOATINGWINDOW
import com.google.android.accessibility.ext.utils.MMKVConst.KEEP_ALIVE_BY_NOTIFICATION
import com.google.android.accessibility.ext.utils.MMKVConst.READNOTIFICATIONBAR
import com.google.android.accessibility.ext.utils.MMKVConst.UPDATE_SCOPE
import com.google.android.accessibility.ext.utils.MMKVConst.UPDATE_VALUE
import com.google.android.accessibility.notification.ClearNotificationListenerServiceImp
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import com.hjq.permissions.tools.PermissionUtils
import java.util.function.Consumer
import kotlin.math.max

object AliveUtils {


    /*
    * 指定要启动的Activity
    * */
    @JvmOverloads
    @JvmStatic
    fun openAliveActivity(showReadBar : Boolean = false,notificationServiceClass : Class<out NotificationListenerService> = ClearNotificationListenerServiceImp::class.java) {
        // 创建一个Intent，指定要启动的Activity
        val intent = Intent(appContext, AliveActivity::class.java)
        intent.putExtra(MMKVConst.NOTIFICATION_SERVICE_CLASS, notificationServiceClass)
        intent.putExtra(MMKVConst.SHOW_READ_NOTIFICATION,showReadBar)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }


    /*
    * 判断指定辅助服务是否已开启
    *
    * @param accessibilityServiceClass 辅助服务类
    * */
    @JvmOverloads
    @JvmStatic
     fun hasOpenService(@NonNull context: Context = appContext, accessibilityServiceClass : Class<out AccessibilityService>): Boolean {
         //返回值是一个包含所有已启用无障碍服务包名的字符串
        val enabledNotificationListeners = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (TextUtils.isEmpty(enabledNotificationListeners)) {
            toast(msg = "辅助服务列表为空")
            return false
        }
         val mAccessibilityServiceClassName = accessibilityServiceClass.name

        val serviceClassName: String? =
            if (PermissionUtils.isClassExist(mAccessibilityServiceClassName)) mAccessibilityServiceClassName else null
        // hello.litiaotiao.app/hello.litiaotiao.app.LttService:com.hjq.permissions.demo/com.hjq.permissions.demo.DemoAccessibilityService
        val allComponentNameArray =
            enabledNotificationListeners.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
        for (component in allComponentNameArray) {
            //component 是包含包名的/
            // componentName.className 不包含包名
            val componentName = ComponentName.unflattenFromString(component) ?: continue

            if (componentName != null) {
                if (serviceClassName != null) {
                    if (context.packageName == componentName.packageName && serviceClassName == componentName.className) {
                        return true
                    }
                } else if (context.packageName == componentName.packageName) {
                    return true
                }
            }
        }
        return false
    }

    @JvmOverloads
    @JvmStatic
    fun openAccessibility(context: Activity, accessibilityServiceClass : Class<out AccessibilityService>): Boolean {
        var isGranted = false
//        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//        context.startActivity(intent)

        val permission = easyRequestPermission(
            context,
            PermissionLists.getBindAccessibilityServicePermission(accessibilityServiceClass),
            "无障碍服务"
        )
        if (permission) {
            isGranted = true
        }else{
            isGranted = false
        }

        return isGranted
    }
    /*
    * 读取通知
    * */
    @JvmOverloads
    @JvmStatic
    fun openNotificationListener(context: Activity, notificationServiceClass : Class<out NotificationListenerService>): Boolean {
        var isGranted = false
//        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//        context.startActivity(intent)

        val permission = easyRequestPermission(
            context,
            PermissionLists.getBindNotificationListenerServicePermission(notificationServiceClass),
            "读取通知服务"
        )
        if (permission) {
            isGranted = true
        }else{
            isGranted = false
        }

        return isGranted
    }
    /*
    * 读取敏感通知  Android 15 引入  暂未实现
    * */
    @JvmOverloads
    @JvmStatic
    fun openSensitiveNotificationListener(context: Activity, notificationServiceClass : Class<out NotificationListenerService>): Boolean {
        var isGranted = false
//        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//        context.startActivity(intent)

        val permission = easyRequestPermission(
            context,
            PermissionLists.getBindNotificationListenerServicePermission(notificationServiceClass),
            "读取通知服务"
        )
        if (permission) {
            isGranted = true
        }else{
            isGranted = false
        }

        return isGranted
    }

    @JvmOverloads
    @JvmStatic
    fun startFGAlive(context: Context = appContext, enable: Boolean) {
        var fgs_intent = Intent(context, AliveFGService::class.java)
        //===
        if (enable){
            //启动服务
            if (!fgs_ison) {
                fgs_intent!!.putExtra("Foreground", context.getString(R.string.quanxian10))
                // Android 8.0使用startForegroundService在前台启动新服务
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    //调用context.startForegroundService(intent)启动服务，
                    // 那么该服务（AliveFGService）在 5 秒内必须调用 startForeground() 方法
                    //否则会抛出异常
                    context.startForegroundService(fgs_intent)
                } else {
                    //启动服务，如果服务尚未创建，则会触发其 onCreate() 方法
                    context.startService(fgs_intent)
                }
            } else {
                toast(context, context.getString(R.string.quanxian11))
            }
            //===
        }else{
            //=== 停止服务
            if (!fgs_ison) {
                toast(context, context.getString(R.string.quanxian12))
            } else {
                toast(context, context.getString(R.string.quanxian13))
                //停止服务 这将触发服务的 onDestroy() 方法，释放资源并关闭前台通知
                context.stopService(fgs_intent)
            }
            //===

        }





    }


    private var ignoreView: View? = null
    private var windowManager: WindowManager? = null
    @JvmStatic
    fun keepAliveByFloatingWindow(service: Service?,enable: Boolean, isAccessibility: Boolean) {
        var myCtx :Context? = null
        if (service != null){
            myCtx = service
        }else{
            myCtx = appContext
//            toast(appContext, appContext.getString(R.string.quanxian32))
        }
        if (myCtx==null){
            return
        }
        if (windowManager==null){
            windowManager = myCtx.getSystemService<WindowManager>(WindowManager::class.java)
        }

        if (enable) {
            val lp = WindowManager.LayoutParams()
            lp.flags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)


            lp.gravity = Gravity.START or Gravity.TOP
            lp.format = PixelFormat.TRANSPARENT
            lp.alpha = 0f
            lp.width = 0
            lp.height = 0
            lp.x = 0
            lp.y = 0

            if (isAccessibility){
                lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                ignoreView = View(myCtx)
            }else{
                if (Build.VERSION.SDK_INT >= 23) {
                    // 检查Android 6悬浮窗权限
//                    val permissionCheck = ContextCompat.checkSelfPermission(
//                        myCtx,
//                        Manifest.permission.SYSTEM_ALERT_WINDOW
//                    )
//
//                    if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
//                        toast(myCtx, myCtx.getString(R.string.quanxian33))
//                        return
//                    }
                    if (!Settings.canDrawOverlays(myCtx)){
                        toast(myCtx, appContext.getString(R.string.quanxian34))
                        return
                    }
                }
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                //1使用Context参数构造
                ignoreView = View(myCtx)
                //2使用 ViewBinding 加载布局
//                val binding = LogOverlayBinding.inflate(LayoutInflater.from(myCtx))
//                ignoreView = binding.root

                //3使用 LayoutInflater 加载布局
//                val inflater = myCtx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
//                ignoreView = inflater.inflate(R.layout.log_overlay, null)


            }

            ignoreView?.setBackgroundColor(Color.TRANSPARENT)
            windowManager?.addView(ignoreView, lp)
        } else if (ignoreView != null) {
            windowManager?.removeView(ignoreView)
            ignoreView = null
            windowManager = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @JvmStatic
    fun keepAliveByNotification_CLS(service: Service?, enable: Boolean, intentCla: Class<*>?) {
        if (service == null) {
            return
        }

        val NOTIFICATION_ID = 0x06
        val CHANNEL_ID = service.getString(R.string.wendingrun1)
        val CHANNEL_NAME = service.getString(R.string.wendingrun2)
        val CHANNEL_DESCRIPTION = service.getString(R.string.wendingrun3)

        if (enable) {
            val notificationManager = service?.getSystemService(
                NotificationManager::class.java
            )
            //创建通知
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(service,CHANNEL_ID)
            } else {
                Notification.Builder(service)
            }
            builder.setOngoing(true)
            builder.setAutoCancel(false)
            //图标不可省略,否则会显示为默认格式
            builder.setSmallIcon(service.applicationInfo.icon)
//            builder.setSmallIcon(R.drawable.se_btn)
//            builder.setContentTitle("后台稳定运行通知")
            builder.setContentTitle(MMKVUtil.get(MMKVConst.FORGROUNDSERVICETITLE, service.getString(R.string.wendingrun2)))
            //通知内容
            builder.setContentText(MMKVUtil.get(MMKVConst.FORGROUNDSERVICECONTENT, service.getString(R.string.wendingrun4)))
            var intent: Intent? = null
            if (intentCla!=null){
                intent = Intent(service, intentCla)
            } else{
                val pm = service.packageManager
                intent = pm.getLaunchIntentForPackage(service.packageName)
//                intent?.component?.className
            }
            if (intent != null){
                val pendingIntent: PendingIntent
                pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_MUTABLE)
                } else {
                    PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                }
                builder.setContentIntent(pendingIntent)
            }



            //创建通知渠道
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(CHANNEL_ID)
                val channel = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESCRIPTION
                }
                notificationManager?.createNotificationChannel(channel)
            }

            // api >= 34
            if (Build.VERSION.SDK_INT >= 34) {
                service?.startForeground(NOTIFICATION_ID, builder.build(), FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

            }
            // api <= 33
            else {
                service?.startForeground(NOTIFICATION_ID, builder.build())
            }
            fgs_ison =  true

        }
        else {

            try {
                // 尝试停止前台服务，并添加日志记录以追踪此操作
                if (Build.VERSION.SDK_INT >= 26) {
                    service?.stopForeground(STOP_FOREGROUND_REMOVE)
                }else{
                    service?.stopForeground(true)
                }
            }  catch (e: Exception) {

            }
            fgs_ison =  false
        }
    }


    @JvmStatic
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val parts2 = v2.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        val maxLength = max(parts1.size.toDouble(), parts2.size.toDouble()).toInt()
        for (i in 0 until maxLength) {
            val num1 = if (i < parts1.size) parts1[i].toInt() else 0
            val num2 = if (i < parts2.size) parts2[i].toInt() else 0

            if (num1 != num2) {
                return Integer.compare(num1, num2)
            }
        }
        return 0
    }


    @JvmOverloads
    @JvmStatic
    fun toast(context: Context = appContext, @StringRes int: Int){
        // 确保在UI线程执行
//        runOnUIThread(context) {
//            Toast.makeText(context, int, Toast.LENGTH_SHORT).show()
//        }
        //新版方案
        ContextCompat.getMainExecutor(context).execute {
            Toast.makeText(context, int, Toast.LENGTH_SHORT).show()
        }
    }
    @JvmOverloads
    @JvmStatic
    fun toast(context: Context = appContext,msg: String){
        // 确保在UI线程执行
//        runOnUIThread(context) {
//            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
//        }
        //新版方案
        ContextCompat.getMainExecutor(context).execute {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    @JvmOverloads
    @JvmStatic
    fun toast(context: Context = appContext, msg: String, time: Int){
        // 确保在UI线程执行
//        runOnUIThread(context) {
//            Toast.makeText(context, msg, time).show()
//        }
        //新版方案
        ContextCompat.getMainExecutor(context).execute {
            Toast.makeText(context, msg, time).show()
        }
    }


    // 辅助函数：确保在UI线程执行
    @JvmStatic
    fun runOnUIThread(context: Context, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            Handler(Looper.getMainLooper()).post {
                action()
            }
        }
    }

    @JvmStatic
    fun easyPermission(context: Activity): Boolean {
        var isGranted = false
        if (Build.VERSION.SDK_INT >= 33){
            XXPermissions.with(context)
                // 申请单个权限
                .permission(PermissionLists.getReadMediaAudioPermission())
//                .permission(Permission.READ_MEDIA_VIDEO)
//                .permission(Permission.READ_MEDIA_IMAGES)
                // 设置不触发错误检测机制（局部设置）
                //.unchecked()
                .request(object : OnPermissionCallback {

                     fun onGranted(permissions: MutableList<IPermission>, allGranted: Boolean) {
                        if (!allGranted) {
                            isGranted = false
                            toast(appContext,"获取部分权限成功，但部分权限未正常授予")
                            return
                        }
                        isGranted = true
//                        toast(appContext,"获取读取音频权限成功")
                    }

                     fun onDenied(permissions: MutableList<IPermission>, doNotAskAgain: Boolean) {
                        if (doNotAskAgain) {
                            isGranted = false
                            toast(appContext,"被永久拒绝授权，请手动授予读取音频权限")
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(context, permissions)
                        } else {
                            isGranted = false
                            toast(appContext,"获取读取音频权限失败")
                        }
                    }

                    override fun onResult(
                        grantedList: MutableList<IPermission>,
                        deniedList: MutableList<IPermission>
                    ) {
                        val allGranted = deniedList.isEmpty()
                        if (!allGranted) {
                            // 在这里处理权限请求失败的逻辑
                            isGranted = false
                            // 判断请求失败的权限是否被用户勾选了不再询问的选项
                            val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(context, deniedList)

                            // ......
                            if (doNotAskAgain) {
                                toast(appContext,"读取音频权限被永久拒绝授权，请手动授予!")
                                // 如果是被永久拒绝就跳转到应用权限系统设置页面
                                XXPermissions.startPermissionActivity(context,deniedList)
                            }else{
                                toast(appContext,"音频权限获取失败")
                            }

                        }else{
                            // 在这里处理权限请求成功的逻辑
                            isGranted = true
                            toast(appContext,"音频权限获取成功")
                        }
                    }

                })

        }
        else{

            XXPermissions.with(context)
                // 申请读写权限
                .permission(PermissionLists.getReadExternalStoragePermission())
//                .permission(Permission.WRITE_EXTERNAL_STORAGE)
                // 设置不触发错误检测机制（局部设置）
                //.unchecked()
                .request(object : OnPermissionCallback {

                     fun onGranted(permissions: MutableList<IPermission>, allGranted: Boolean) {
                        if (!allGranted) {
                            isGranted = false
                            toast(appContext,"获取部分权限成功，但部分权限未正常授予")
                            return
                        }
                        isGranted = true
                        toast(appContext,"获取读取外部存储权限成功")
                    }

                     fun onDenied(permissions: MutableList<IPermission>, doNotAskAgain: Boolean) {
                        if (doNotAskAgain) {
                            isGranted = false
                            toast(appContext,"被永久拒绝授权，请手动授予读取外部存储权限")
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(context, permissions)
                        } else {
                            isGranted = false
                            toast(appContext,"获取读取外部存储权限失败")
                        }
                    }

                    override fun onResult(
                        grantedList: MutableList<IPermission>,
                        deniedList: MutableList<IPermission>
                    ) {
                        val allGranted = deniedList.isEmpty()
                        if (!allGranted) {
                            // 在这里处理权限请求失败的逻辑
                            isGranted = false
                            // 判断请求失败的权限是否被用户勾选了不再询问的选项
                            val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(context, deniedList)

                            // ......
                            if (doNotAskAgain) {
                                toast(appContext,"外部存储权限被永久拒绝授权，请手动授予!")
                                // 如果是被永久拒绝就跳转到应用权限系统设置页面
                                XXPermissions.startPermissionActivity(context,deniedList)
                            }else{
                                toast(appContext,"外部存储权限获取失败")
                            }

                        }else{
                            // 在这里处理权限请求成功的逻辑
                            isGranted = true
                            toast(appContext,"外部存储权限获取成功")
                        }
                    }
                })
        }
        return isGranted
    }

    @JvmStatic
    fun easyRequestPermission(context: Activity, permission:IPermission, permissionName: String): Boolean {
        var isGranted = false
        if (Build.VERSION.SDK_INT >= 23){
            XXPermissions.with(context)
                // 申请单个权限
                .permission(permission)
                // 设置不触发错误检测机制（局部设置）
                //.unchecked()
                .request(object : OnPermissionCallback {

                     fun onGranted(permissions: MutableList<IPermission>, allGranted: Boolean) {
                        if (!allGranted) {
                            isGranted = false
                            toast(appContext,"获取部分权限成功，但部分权限未正常授予")
                            return
                        }
                        isGranted = true
//                        toast(appContext,"获取读取音频权限成功")
                    }

                     fun onDenied(permissions: MutableList<IPermission>, doNotAskAgain: Boolean) {
                        if (doNotAskAgain) {
                            isGranted = false
                            toast(appContext,"被永久拒绝授权，请手动授予权限")
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(context, permissions)
                        } else {
                            isGranted = false
                            toast(appContext,"获取权限失败")
                        }
                    }

                    override fun onResult(
                        grantedList: MutableList<IPermission>,
                        deniedList: MutableList<IPermission>
                    ) {
                        val allGranted = deniedList.isEmpty()
                        if (!allGranted) {
                            // 在这里处理权限请求失败的逻辑
                            isGranted = false
                            // 判断请求失败的权限是否被用户勾选了不再询问的选项
                            val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(context, deniedList)
                            if (doNotAskAgain) {
                                toast(appContext,permissionName+"权限被永久拒绝授权，请手动授予!")
                                // 如果是被永久拒绝就跳转到应用权限系统设置页面
                                XXPermissions.startPermissionActivity(context,deniedList)
                            }else{
                                toast(appContext,permissionName+"获取失败")
                            }

                        }else{
                            // 在这里处理权限请求成功的逻辑
                            isGranted = true
                            toast(appContext,permissionName+"获取成功")
                        }
                    }

                })

        }

        return isGranted
    }

    @JvmStatic
    fun requestUpdateKeepAliveByNotification(enable: Boolean): Boolean {
        try {
            val contentValues = ContentValues()
            contentValues.put(UPDATE_SCOPE, KEEP_ALIVE_BY_NOTIFICATION)
            contentValues.put(UPDATE_VALUE, enable)
            val re: Int = appContext.getContentResolver().update(
                    Uri.parse(contentProviderAuthority),
                    contentValues,
                    null,
                    null
                )
            return re > 0
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }
        return false
    }
    @JvmStatic
    fun requestUpdateKeepAliveByFloatingWindow(enable: Boolean): Boolean {
        try {
            val contentValues = ContentValues()
            contentValues.put(UPDATE_SCOPE, KEEP_ALIVE_BY_FLOATINGWINDOW)
            contentValues.put(UPDATE_VALUE, enable)
            val re: Int =
                appContext.getContentResolver().update(
                    Uri.parse(contentProviderAuthority),
                    contentValues,
                    null,
                    null
                )
            return re > 0
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }
        return false
    }
    @JvmStatic
    fun getKeepAliveByNotification(): Boolean {
        val preferences: SharedPreferences =
            appContext.getSharedPreferences(
                appContext.getPackageName(),
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        return preferences.getBoolean(KEEP_ALIVE_BY_NOTIFICATION, false)
    }

    @JvmStatic
    fun setKeepAliveByNotification(enable: Boolean): Boolean {
        val preferences: SharedPreferences =
            appContext.getSharedPreferences(
                appContext.getPackageName(),
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        preferences.edit().putBoolean(KEEP_ALIVE_BY_NOTIFICATION, enable).apply()
        return true
    }
    @JvmStatic
    fun getKeepAliveByFloatingWindow(): Boolean {
        val preferences: SharedPreferences =
            appContext.getSharedPreferences(
                appContext.getPackageName(),
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        return preferences.getBoolean(KEEP_ALIVE_BY_FLOATINGWINDOW, false)
    }
    @JvmStatic
    fun setKeepAliveByFloatingWindow(enable: Boolean): Boolean {
        val preferences: SharedPreferences =
            appContext.getSharedPreferences(
                appContext.getPackageName(),
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        preferences.edit().putBoolean(KEEP_ALIVE_BY_FLOATINGWINDOW, enable).apply()
        return true
    }

    @JvmStatic
    fun getAC_AliveNotification(): Boolean {
        return SPUtils.getBoolean(CLEARAUTOBAOHUOISON,false)
    }
    @JvmStatic
    fun setAC_AliveNotification(enable: Boolean): Boolean {
        SPUtils.putBoolean(CLEARAUTOBAOHUOISON,enable)
        return true
    }

    @JvmStatic
    fun getReadNotification(): Boolean {
        return SPUtils.getBoolean(READNOTIFICATIONBAR,false)
    }
    @JvmStatic
    fun setReadNotification(enable: Boolean): Boolean {
        SPUtils.putBoolean(READNOTIFICATIONBAR,enable)
        return true
    }


    @JvmStatic
    fun getFirstInstallTime(context: Context): Long? {
        var packageInfo: PackageInfo? = null
        var firstInstallTime: Long = 0
        try {
            packageInfo = context.getPackageManager().getPackageInfo(context.packageName, 0)
            firstInstallTime = packageInfo.firstInstallTime
        } catch (e: PackageManager.NameNotFoundException) {
            firstInstallTime = System.currentTimeMillis()
        }

//        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
//        val firstInstallTime = packageInfo.firstInstallTime //应用第一次安装的时间
//        val lastUpdateTime = packageInfo.lastUpdateTime   //应用最后一次更新的时间
        return firstInstallTime

    }

    @JvmStatic
    fun piSend(pendingIntent: PendingIntent?) {
        if (pendingIntent == null) return

        // 尝试获取 IntentSender，若为 null 则回退到 pendingIntent.send()
        val intentSender = pendingIntent.intentSender
        if (intentSender == null) {
            // 回退：直接发送 PendingIntent（兼容旧逻辑）
            //其实也就是Android 12 以下
            try {
                pendingIntent.send()
            } catch (e: PendingIntent.CanceledException) {
                Log.e("YourTag", "PendingIntent canceled", e)
            }
            return
        }

        // 选择合适的 options（根据系统版本）
        val optionsBundle: Bundle? = try {
            val options = ActivityOptions.makeBasic()
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    // Android 14+: 推荐优先使用 ALLOW_IF_VISIBLE，除非确实需要打断用户才用 ALLOW_ALWAYS
                    options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    )
                    options.toBundle()
                }
                Build.VERSION.SDK_INT >= 31 -> {
                    // Android 12~13: 使用原先的常量（还未废弃）
                    options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    options.toBundle()
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w("YourTag", "Failed to build ActivityOptions, falling back", e)
            null
        }

        try {
            // 使用 Application Context 启动 IntentSender 时通常需要 NEW_TASK 标志
            // 第三个参数是 flagsMask，第四个参数是 flagsValues —— 我们同时设置 mask 与 value 为 FLAG_ACTIVITY_NEW_TASK
            val newTaskFlag = Intent.FLAG_ACTIVITY_NEW_TASK
            appContext.startIntentSender(
                intentSender,
                /* fillInIntent = */ null,
                /* flagsMask = */ newTaskFlag,
                /* flagsValues = */ newTaskFlag,
                /* extraFlags = */ 0,
                /* options = */ optionsBundle
            )
        } catch (e: SecurityException) {
            // 系统可能拒绝后台直接启动 Activity（尤其是没有合适可见性时）
            Log.e("YourTag", "SecurityException starting activity from PendingIntent", e)
            // 回退：尝试直接 send（注意：这不会携带 ActivityOptions）
            try {
                pendingIntent.send()
            } catch (ex: PendingIntent.CanceledException) {
                Log.e("YourTag", "PendingIntent canceled on fallback send", ex)
            }
        } catch (e: IntentSender.SendIntentException) {
            Log.e("YourTag", "SendIntentException starting activity from PendingIntent", e)
        } catch (e: Exception) {
            Log.e("YourTag", "Unexpected exception starting activity from PendingIntent", e)
        }
    }


    @JvmStatic
    fun showCheckDialog(activity: Activity,tvRes: Int,imgRes: Int,titleRes: Int,btnValue: Int) {
        // 加载自定义视图
        val view: View = activity.layoutInflater.inflate(R.layout.dialog_image_xpq, null)

        val tvimageView = view.findViewById<TextView>(R.id.tvimageView)
        tvimageView.text = activity.getString(tvRes)

        // 获取ImageView并设置图片
        val imageView = view.findViewById<ImageView>(R.id.imageView)
        imageView.setImageResource(imgRes) // 替换为实际图片资源ID

        // 创建AlertDialog Builder
        val builder = AlertDialog.Builder(activity)
        builder.setView(view)
            .setTitle(activity.getString(titleRes))
            .setPositiveButton(
                activity.getString(R.string.ok)
            ) { dialog, which ->
                dialog.dismiss()
                when (btnValue) {
                    BTN_AUTOSTART  -> {
                        //自启动管理界面
                        Utilshezhi.startToAutoStartSetting(activity)
                    }
                    BTN_RECENTS  ->{
                        //打开最近任务列表
                        if (SelectToSpeakServiceAbstract.instance == null) {
                            AliveUtils.toast(appContext, appContext.getString(R.string.lockapp))
                        } else {
                            AliveUtils.toast(appContext, appContext.getString(R.string.quanxian31))
                            SelectToSpeakServiceAbstract.instance!!.performGlobalAction(GLOBAL_ACTION_RECENTS)
                        }
                    }

                    BTN_PERMISSION  -> Utilshezhi.gotoPermission(activity)
                 }
            }
            .setNegativeButton(
                activity.getString(R.string.cancel)
            ) { dialog, which ->
                dialog.dismiss()
            }

        // 只在 btnValue 为 1 或 2 时添加 Neutral 按钮
        if (btnValue == BTN_AUTOSTART || btnValue == BTN_RECENTS || btnValue == BTN_PERMISSION) {
            builder.setNeutralButton(activity.getString(R.string.sxzxpq)) { dialog, _ ->
                dialog.dismiss()
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mp.weixin.qq.com/s/CbRFGUrqoKJie3JTdRmWPA"))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    toast(appContext, appContext.getString(R.string.nowebb))
                }
            }
        }


        // 显示对话框
        val alertDialog = builder.create()
        alertDialog.show()
    }

    @JvmStatic
    fun setExcludeFromRecents(exclude: Boolean) {
        val activityManager: ActivityManager = appContext.getSystemService<ActivityManager?>(ActivityManager::class.java)
        activityManager.getAppTasks().forEach(Consumer { e: AppTask? -> e!!.setExcludeFromRecents(exclude) })
    }


}