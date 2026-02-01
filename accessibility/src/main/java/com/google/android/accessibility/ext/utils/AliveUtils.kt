package com.google.android.accessibility.ext.utils


import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.app.admin.DevicePolicyManager
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
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.ForgroundserviceDialogXpqBinding
import com.google.android.accessibility.ext.activity.AliveActivity
import com.google.android.accessibility.ext.activity.AliveFGService
import com.google.android.accessibility.ext.activity.AliveFGService.Companion.fgs_ison
import com.google.android.accessibility.ext.activity.MyDeviceAdminReceiverXpq
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.contentProviderAuthority
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_ACCESSIBILITY
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_AUTOSTART
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_PERMISSION
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_RECENTS
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_RECENT_HIDE
import com.google.android.accessibility.ext.utils.MMKVConst.BTN_RECENT_HIDE_PLUS
import com.google.android.accessibility.ext.utils.MMKVConst.CLEARAUTOBAOHUOISON
import com.google.android.accessibility.ext.utils.MMKVConst.KEEP_ALIVE_BY_FLOATINGWINDOW
import com.google.android.accessibility.ext.utils.MMKVConst.KEEP_ALIVE_BY_NOTIFICATION
import com.google.android.accessibility.ext.utils.MMKVConst.READNOTIFICATIONBAR
import com.google.android.accessibility.ext.utils.MMKVConst.SP_FILE_NAME
import com.google.android.accessibility.ext.utils.MMKVConst.TASKHIDE_BROADCAST
import com.google.android.accessibility.ext.utils.MMKVConst.TASKHIDE_BROADCAST_PLUS
import com.google.android.accessibility.ext.utils.MMKVConst.TASKHIDE_LIST
import com.google.android.accessibility.ext.utils.MMKVConst.UPDATE_SCOPE
import com.google.android.accessibility.ext.utils.MMKVConst.UPDATE_VALUE
import com.google.android.accessibility.ext.utils.NotificationUtilXpq.isNotificationListenerEnabled
import com.google.android.accessibility.notification.ClearNotificationListenerServiceImp
import com.google.android.accessibility.receiver.XpqBootReceiver
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.google.android.accessibility.selecttospeak.accessibilityService
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import com.hjq.permissions.tools.PermissionUtils
import org.json.JSONArray
import java.util.Locale
import kotlin.math.max

@SuppressLint("StaticFieldLeak")
object AliveUtils {


    /*
    * 指定要启动的Activity
    * */
    @JvmOverloads
    @JvmStatic
    fun openAliveActivity(showTaskHide : Boolean = false,
                          showReadBar : Boolean = false,
                          notificationServiceClass : Class<out NotificationListenerService> = ClearNotificationListenerServiceImp::class.java,
                          showOpenAccessibility : Boolean = false,
                          imgRes: Int = R.drawable.recenthidexpq) {
        // 创建一个Intent，指定要启动的Activity
        val intent = Intent(appContext, AliveActivity::class.java)
        intent.putExtra(MMKVConst.NOTIFICATION_SERVICE_CLASS, notificationServiceClass)
        intent.putExtra(MMKVConst.SHOW_READ_NOTIFICATION,showReadBar)
        intent.putExtra(MMKVConst.SHOW_TASK_HIDE,showTaskHide)
        intent.putExtra(MMKVConst.SHOW_OPEN_ACCESSIBILITY,showOpenAccessibility)
        intent.putExtra(MMKVConst.SEND_IMAGE_ID,imgRes)
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
    fun openAccessibility(context: Activity, accessibilityServiceClass : Class<out AccessibilityService>?): Boolean {
        var isGranted = false
        if (accessibilityServiceClass==null){
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return false
        }


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

    private var wakeLock: PowerManager.WakeLock? = null
    private var ignoreView: View? = null
    private var windowManager: WindowManager? = null
    private var lastCreatedByAccessibility: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmOverloads
    @JvmStatic
    fun keepAliveByFloatingWindow(ctx: Context? = appContext, enable: Boolean,activity: Activity? = null) {
        if (ctx == null) return
        val appCtx = ctx.applicationContext

        // 当前调用是否为 AccessibilityService
        val actuallyAccessibility = ctx is AccessibilityService

        // WindowManager 上下文选择
        val wmContext = if (actuallyAccessibility) ctx else appCtx

        // lazy 获取 windowManager
        if (windowManager == null) {
            windowManager = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    wmContext.getSystemService(WindowManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    wmContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                }
            } catch (t: Throwable) {
                null
            }
        }

        if (!enable) {
            // 移除
            if (ignoreView != null) {
                mainHandler.post {
                    try {
                        (ignoreView?.parent as? ViewGroup)?.removeView(ignoreView)
                        windowManager?.removeViewImmediate(ignoreView)
                    } catch (t: Throwable) {
                        Log.e("KeepAlive", "removeView failed", t)
                    } finally {
                        ignoreView = null
                        lastCreatedByAccessibility = false
                    }
                }
            }
            // 释放锁
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Throwable) {}
            try {
              activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Throwable) {}

            return
        }

        // 已存在时的逻辑
        if (ignoreView != null) {
            if (lastCreatedByAccessibility && !actuallyAccessibility) {
                // 已有无障碍创建，不允许普通 Context 覆盖
                Log.d("KeepAlive", "已有无障碍创建的悬浮窗，忽略普通 Context 创建请求")
                return
            }
            if (lastCreatedByAccessibility == actuallyAccessibility) {
                // 类型相同，直接返回
                return
            }
            if (actuallyAccessibility && !lastCreatedByAccessibility) {
                // 无障碍要覆盖非无障碍 -> 先移除再重建
                mainHandler.post {
                    try {
                        (ignoreView?.parent as? ViewGroup)?.removeView(ignoreView)
                        windowManager?.removeViewImmediate(ignoreView)
                    } catch (_: Throwable) { }
                    ignoreView = null
                    lastCreatedByAccessibility = false
                }
                mainHandler.postDelayed({ keepAliveByFloatingWindow(ctx, true) }, 60)
                return
            }
            return
        }

        // 新建前权限检查（普通 Context 分支）
        if (!actuallyAccessibility) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlayPermission(appCtx)) {
                toast(appCtx, appCtx.getString(R.string.quanxian34))
                return
            }
        }

         fun postAddViewWhenAccessibilityReady(
            service: AccessibilityService,
            wm: WindowManager,
            view: View,
            lp: WindowManager.LayoutParams,
            onSuccess: () -> Unit
        ) {
            val handler = Handler(Looper.getMainLooper())
            var retryCount = 0
            val maxRetry = 20
            val retryDelay = 50L

            fun tryAdd() {
                //if (!service.isServiceConnected) return
                if (view.parent != null) return

                // 🔑 核心：token 是否就绪
                if (service.rootInActiveWindow == null) {
                    if (retryCount++ < maxRetry) {
                        handler.postDelayed({ tryAdd() }, retryDelay)
                    } else {
                        Log.e("KeepAlive", "Accessibility window not ready, give up addView")
                    }
                    return
                }

                try {
                    wm.addView(view, lp)
                    onSuccess()
                } catch (t: Throwable) {
                    Log.e("KeepAlive", "addView failed after ready", t)
                }
            }

            handler.post { tryAdd() }
        }


        val lp = WindowManager.LayoutParams().apply {
            width = 1
            height = 1
            flags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            if (MMKVUtil.get(MMKVConst.XPQ_SCREEN_ON,false)) {
                flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON


                val pm = wmContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                // 使用 PARTIAL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP 来兼容性更好且不直接使用过时常量
                try {
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "com.example.keepalive:wakelock"
                    )
                } catch (t: Throwable) {
                    // 作为降级，尝试使用 FULL_WAKE_LOCK（deprecated）
                    wakeLock = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "com.example.keepalive:wakelock"
                    )
                }
                try {
                    if (wakeLock?.isHeld != true) {
                        // 持续持有（直到用户手动停止服务）
                        wakeLock?.acquire()
                    }
                } catch (_: Throwable) {}
                try {
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } catch (_: Throwable) {}

            }else {
                // 释放锁
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                    }
                } catch (_: Throwable) {}
                try {
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } catch (_: Throwable) {}
            }
            gravity = Gravity.START or Gravity.TOP
            format = PixelFormat.TRANSPARENT
            alpha = 0f
            x = 0
            y = 0

            type = if (actuallyAccessibility) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            } else {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                    else -> {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                }
            }
        }

        val viewContext = if (actuallyAccessibility) {
            ctx   // 🔑 AccessibilityService 本身
        } else {
            appCtx
        }

        ignoreView = View(viewContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }


        //ignoreView = View(appCtx).apply { setBackgroundColor(Color.TRANSPARENT) }
      //==
        val wm = windowManager ?: return

        if (actuallyAccessibility && ctx is AccessibilityService) {
            // ⭐ 无障碍：自动等待 token
            postAddViewWhenAccessibilityReady(
                service = ctx,
                wm = wm,
                view = ignoreView!!,
                lp = lp
            ) {
                lastCreatedByAccessibility = true
                Log.d("KeepAlive", "overlay added safely (accessibility)")
            }
        } else {
            // ⭐ 普通 Context：保持你原来的行为
            mainHandler.post {
                try {
                    if (ignoreView?.parent != null) return@post
                    wm.addView(ignoreView, lp)
                    lastCreatedByAccessibility = false
                    Log.d("KeepAlive", "overlay added (normal context)")
                } catch (t: Throwable) {
                    Log.e("KeepAlive", "addView failed", t)
                }
            }
        }

        //==



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
    fun hasOverlayPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Settings.canDrawOverlays(ctx.applicationContext)
            } catch (t: Throwable) {
                false
            }
        } else true
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
    @JvmOverloads
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
                                if (deniedList.contains(PermissionLists.getBindDeviceAdminPermission(MyDeviceAdminReceiverXpq::class.java))){
                                    openSettingAdmin(appContext)
                                }
                                else if (deniedList.contains(PermissionLists.getSystemAlertWindowPermission())){
                                    openSettingFloat(appContext)
                                }
                            }


                        }else{
                            // 在这里处理权限请求成功的逻辑
                            isGranted = true
                            toast(appContext,permissionName+"获取成功")
                        }
                    }

                })

        } else{
            isGranted = true
        }

        return isGranted
    }
    @JvmOverloads
    @JvmStatic
    fun easyRequestPermission(context: Fragment, permission:IPermission, permissionName: String): Boolean {
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
                            toast(appContext,permissionName+"获取失败")

                            if (deniedList.contains(PermissionLists.getBindDeviceAdminPermission(MyDeviceAdminReceiverXpq::class.java))){
//                                XXPermissions.startPermissionActivity(context,deniedList)
                                openSettingAdmin(appContext)
                            }






                        }else{
                            // 在这里处理权限请求成功的逻辑
                            isGranted = true
                            toast(appContext,permissionName+"获取成功")
                        }
                    }

                })

        } else{
            isGranted = true
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
    @JvmOverloads
    @JvmStatic
    fun requestUpdateKeepAliveByTaskHide(enable: Boolean, list: Collection<String> = emptyList()): Boolean {
        try {
            val contentValues = ContentValues()
            contentValues.put(UPDATE_SCOPE, MMKVConst.KEY_OPEN_YIN_CANG)
            contentValues.put(UPDATE_VALUE, enable)
            //val list = listOf("A", "B", "C")
            val jsonStr = JSONArray(list).toString()   // 结果：["A","B","C"]
            contentValues.put(TASKHIDE_LIST, jsonStr)

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
    @JvmOverloads
    @JvmStatic
    fun requestUpdateKeepAliveByTaskHidePlus(enable: Boolean, list: Collection<String> = emptyList()): Boolean {
        try {
            val contentValues = ContentValues()
            contentValues.put(UPDATE_SCOPE, MMKVConst.KEY_OPEN_YIN_CANG_PLUS)
            contentValues.put(UPDATE_VALUE, enable)
            //val list = listOf("A", "B", "C")
            val jsonStr = JSONArray(list).toString()   // 结果：["A","B","C"]
            contentValues.put(TASKHIDE_LIST, jsonStr)

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
    fun getKeepAliveByTaskHide(): Boolean {
      /*  val preferences: SharedPreferences =
            appContext.getSharedPreferences(
                appContext.getPackageName(),
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        val boolean = preferences.getBoolean(KEEP_ALIVE_BY_TASKHIDE, false)*/
        //2
        val hidepre: SharedPreferences =
            appContext.getSharedPreferences(
                SP_FILE_NAME,
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        val boolean = hidepre.getBoolean(MMKVConst.KEY_OPEN_YIN_CANG, false)
        return boolean
    }
    @JvmStatic
    fun setKeepAliveByTaskHide(enable: Boolean): Boolean {
     /*   val preferences: SharedPreferences =
            appContext.getSharedPreferences(
                appContext.getPackageName(),
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        preferences.edit().putBoolean(KEEP_ALIVE_BY_TASKHIDE, enable).apply()*/
        //2
        val hidepre: SharedPreferences =
            appContext.getSharedPreferences(
                SP_FILE_NAME,
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        //commit 和 apply 两者都会先把更改应用到内存的 SharedPreferences 缓存中，
        // 所以同一进程内随后的 getXxx() 立刻能读取到新值
        hidepre.edit().putBoolean(MMKVConst.KEY_OPEN_YIN_CANG, enable).apply()
        return true
    }

    @JvmStatic
    fun getKeepAliveByTaskHidePlus(): Boolean {
        val hidepre: SharedPreferences =
            appContext.getSharedPreferences(
                SP_FILE_NAME,
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        val boolean = hidepre.getBoolean(MMKVConst.KEY_OPEN_YIN_CANG_PLUS, false)
        return boolean
    }
    @JvmStatic
    fun setKeepAliveByTaskHidePlus(enable: Boolean): Boolean {
        val hidepre: SharedPreferences =
            appContext.getSharedPreferences(
                SP_FILE_NAME,
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )
        //commit 和 apply 两者都会先把更改应用到内存的 SharedPreferences 缓存中，
        // 所以同一进程内随后的 getXxx() 立刻能读取到新值
        hidepre.edit().putBoolean(MMKVConst.KEY_OPEN_YIN_CANG_PLUS, enable).apply()
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

        val intentSender = pendingIntent.intentSender

        // 统一定义旧版回退逻辑
        fun oldPiSend() {
            try {
                pendingIntent.send()
            } catch (e: Exception) {
                Log.e("piSend", "Fallback send failed", e)
            }
        }

        // 1. 如果无法获取 IntentSender 或版本低于 31，直接走旧逻辑
        // 特别说明：API 31/32 官方没有暴露显式的后台启动控制 API
        if (intentSender == null || Build.VERSION.SDK_INT < 33) {
            oldPiSend()
            return
        }

        // 2. 构建 ActivityOptions Bundle
        val optionsBundle: Bundle? = try {
            val options = ActivityOptions.makeBasic()
            when {
                // Android 14+ (API 34)
                Build.VERSION.SDK_INT >= 34 -> {
                    // 如果你担心华为/鸿蒙某些机型混淆了 API，可以在这里局部使用反射
                    // 否则直接调用即可
                    options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    )
                    options.toBundle()
                }
                // Android 13 (API 33)
                Build.VERSION.SDK_INT == 33 -> {
                    options.setPendingIntentBackgroundActivityLaunchAllowed(true)
                    options.toBundle()
                }
                else -> null
            }
        } catch (e: Throwable) {
            // 捕捉包括 NoSuchMethodError 在内的所有异常，确保健壮性
            Log.w("piSend", "Failed to set background start options", e)
            null
        }

        // 3. 执行启动
        try {
            val newTaskFlag = Intent.FLAG_ACTIVITY_NEW_TASK
            appContext.startIntentSender(
                intentSender,
                null,
                newTaskFlag,
                newTaskFlag,
                0,
                optionsBundle
            )
        } catch (e: Exception) {
            Log.e("piSend", "startIntentSender failed, trying fallback", e)
            oldPiSend()
        }
    }


    @JvmStatic
    fun showCheckDialog(activity: Activity,tvRes: Int,imgRes: Int,titleRes: Int,btnValue: Int,myImageView: ImageView?=null,tableLayout: View?=null,ic_open: Int=R.drawable.ic_open_xpq,ic_close: Int=R.drawable.ic_close_xpq) {
        // 加载自定义视图
        val view: View = activity.layoutInflater.inflate(R.layout.dialog_image_xpq, null)

        val tvimageView = view.findViewById<TextView>(R.id.tvimageView)
        tvimageView.text = activity.getString(tvRes)

        // 获取ImageView并设置图片
        val imageView = view.findViewById<ImageView>(R.id.imageView)
        imageView.setImageResource(imgRes) // 替换为实际图片资源ID

        val drawableYes = ContextCompat.getDrawable(appContext, ic_open)
        val drawableNo = ContextCompat.getDrawable(appContext, ic_close)

        // 创建AlertDialog Builder
        val builder = AlertDialog.Builder(activity)
        builder.setView(view)
            .setTitle(activity.getString(titleRes))
            .setPositiveButton(
                activity.getString(R.string.ok)
            ) { dialog, which ->
                dialog.dismiss()
                when (btnValue) {
                    BTN_ACCESSIBILITY  -> {
                        //无障碍
                        NotificationUtilXpq.gotoAccessibilitySetting()
                    }
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
                    BTN_RECENT_HIDE  -> {
                        //多任务隐藏
                        //===
                        val keepAliveByTaskHide = true
                        AliveUtils.setKeepAliveByTaskHide(keepAliveByTaskHide)
                        AliveUtils.requestUpdateKeepAliveByTaskHide(keepAliveByTaskHide)
                        AliveUtils.toast(appContext, if (keepAliveByTaskHide) appContext.getString(R.string.quanxiantaskhide) else appContext.getString(R.string.quanxian13))
                        //===
                        if (AliveUtils.getKeepAliveByTaskHide()) {
                            myImageView?.setImageDrawable(drawableYes)
                            tableLayout?.visibility = View.VISIBLE
                        } else {
                            myImageView?.setImageDrawable(drawableNo)
                            tableLayout?.visibility = View.GONE
                        }
                        AliveUtils.sendLibBroadcast()

                    }
                    BTN_RECENT_HIDE_PLUS  -> {
                        //多任务隐藏 PLUS
                        //===
                        val keepAliveByTaskHidePlus = true
                        AliveUtils.setKeepAliveByTaskHidePlus(keepAliveByTaskHidePlus)
                        //执行后,会立即finish所有的activity,包括当前,故,只在软件处于后台运行时,才调用
                        //AliveUtils.requestUpdateKeepAliveByTaskHidePlus(keepAliveByTaskHidePlus)
                        AliveUtils.toast(appContext, if (keepAliveByTaskHidePlus) appContext.getString(R.string.quanxiantaskhidePlus) else appContext.getString(R.string.quanxian13))
                        //===
                        if (AliveUtils.getKeepAliveByTaskHidePlus()) {
                            myImageView?.setImageDrawable(drawableYes)
                        } else {
                            myImageView?.setImageDrawable(drawableNo)
                        }
                        AliveUtils.sendLibBroadcastPlus()


                    }
                 }
            }
            .setNegativeButton(
                activity.getString(R.string.cancel)
            ) { dialog, which ->
                dialog.dismiss()
                when (btnValue) {
                    BTN_RECENT_HIDE  -> {
                        //多任务隐藏
                        //===
                        val keepAliveByTaskHide = false
                        AliveUtils.setKeepAliveByTaskHide(keepAliveByTaskHide)
                        AliveUtils.requestUpdateKeepAliveByTaskHide(keepAliveByTaskHide)
                        AliveUtils.toast(appContext, if (keepAliveByTaskHide) appContext.getString(R.string.quanxiantaskhide) else appContext.getString(R.string.quanxian13))
                        //===
                        if (AliveUtils.getKeepAliveByTaskHide()) {
                            myImageView?.setImageDrawable(drawableYes)
                            tableLayout?.visibility = View.VISIBLE
                        } else {
                            myImageView?.setImageDrawable(drawableNo)
                            tableLayout?.visibility = View.GONE
                        }
                        sendLibBroadcast()
                    }
                    BTN_RECENT_HIDE_PLUS  -> {
                        //多任务隐藏 PLUS
                        //===
                        val keepAliveByTaskHidePlus = false
                        AliveUtils.setKeepAliveByTaskHidePlus(keepAliveByTaskHidePlus)
                        //执行后,会立即finish所有的activity,包括当前,故,只在软件处于后台运行时,才调用
                        //AliveUtils.requestUpdateKeepAliveByTaskHidePlus(keepAliveByTaskHidePlus)
                        AliveUtils.toast(appContext, if (keepAliveByTaskHidePlus) appContext.getString(R.string.quanxiantaskhidePlus) else appContext.getString(R.string.quanxian13))
                        //===
                        if (AliveUtils.getKeepAliveByTaskHidePlus()) {
                            myImageView?.setImageDrawable(drawableYes)
                        } else {
                            myImageView?.setImageDrawable(drawableNo)
                        }
                        AliveUtils.sendLibBroadcastPlus()
                    }
                }

            }

        // 只在 btnValue 为 1 或 2 时添加 Neutral 按钮
        if (btnValue == BTN_AUTOSTART || btnValue == BTN_ACCESSIBILITY || btnValue == BTN_PERMISSION) {
            builder.setNeutralButton(activity.getString(R.string.sxzxpq)) { dialog, _ ->
                dialog.dismiss()

                shouxianzhi()
            }
        }


        // 显示对话框
        val alertDialog = builder.create()
        alertDialog.show()
    }

    // 发广播（库内）
    @JvmStatic
    fun sendLibBroadcast() {
        val intent = Intent(TASKHIDE_BROADCAST)
        //intent.putExtra("eventId", "my_button")
        //intent.putExtra("payload", "value")
        // 推荐加包名来减少被外部监听
        intent.setPackage(appContext.packageName)
        appContext.sendBroadcast(intent)
    }
    @JvmStatic
    fun sendLibBroadcastPlus() {
        val intent = Intent(TASKHIDE_BROADCAST_PLUS)
        //intent.putExtra("eventId", "my_button")
        //intent.putExtra("payload", "value")
        // 推荐加包名来减少被外部监听
        intent.setPackage(appContext.packageName)
        appContext.sendBroadcast(intent)
    }

    @JvmStatic
    fun showAcc_FloatDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setMessage(appContext.getString(R.string.xiangsuxpq))
            .setPositiveButton(appContext.getString(R.string.accessxpq)) { _, _ ->
                //无障碍
                AliveUtils.openAccessibility(activity,null)
            }
            .setNegativeButton(appContext.getString(R.string.floatxpq)) { _, _ ->
                //悬浮窗
                AliveUtils.easyRequestPermission(activity, PermissionLists.getSystemAlertWindowPermission(),"悬浮窗")

            }
            .setNeutralButton(appContext.getString(R.string.sxzxpq)){_, _ ->
                shouxianzhi()
            }
            .show()
    }

    @JvmOverloads
    @JvmStatic
    fun setExcludeFromRecents(
        exclude: Boolean,
        list: Collection<String> = emptyList()
    ) {
        appContext?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return


        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return

        val appTasks = activityManager.appTasks
        appTasks?.forEach { task ->
            if (!exclude) {
                val taskInfo = task.taskInfo
                val base = taskInfo?.baseActivity?.className
                val top = taskInfo?.topActivity?.className
                if (TextUtils.isEmpty(base) || TextUtils.isEmpty(top)) {
                    //activity名字为空,不代表该任务已不存在了
                    task.setExcludeFromRecents(true)
                } else if (base in list || top in list) {
                    task.setExcludeFromRecents(true)
                }else if (list.isNullOrEmpty()){
                    if (base?.contains(".LaunchActivity") == true || base?.contains(".SplashADActivity") == true ||
                        top?.contains(".LaunchActivity") == true || top?.contains(".SplashADActivity") == true
                    ){
                        task.setExcludeFromRecents(true)
                    }
                } else {
                    task.setExcludeFromRecents(exclude)
                }
            } else {
                task.setExcludeFromRecents(exclude)
            }

        }

    }

    @JvmOverloads
    @JvmStatic
    fun setExcludeFromRecentsPlus(
        exclude: Boolean,
        list: Collection<String> = emptyList()
    ) {
        appContext?: return
        //if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return


        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return

        val appTasks = activityManager.appTasks
        appTasks?.forEach { task ->
            if (!exclude) {
                val taskInfo = task.taskInfo
                val base = taskInfo?.baseActivity?.className
                val top = taskInfo?.topActivity?.className
                if (TextUtils.isEmpty(base) || TextUtils.isEmpty(top)) {
                    //activity名字为空,不代表该任务已不存在了
                    task.finishAndRemoveTask()
                } else if (base in list || top in list) {
                    task.finishAndRemoveTask()
                }else if (list.isNullOrEmpty()){
                    if (base?.contains(".LaunchActivity") == true || base?.contains(".SplashADActivity") == true ||
                        top?.contains(".LaunchActivity") == true || top?.contains(".SplashADActivity") == true
                    ){
                        task.finishAndRemoveTask()
                    }
                }
                else {

                }
            } else {
                task.finishAndRemoveTask()
            }

        }

    }


    @JvmStatic
    fun show0Pixl(): Boolean {
        var isShow = false
        if (accessibilityService==null){
            //辅助服务为空
            if (!hasOverlayPermission(appContext)) {
                // 悬浮窗权限未开启
                isShow = false
            } else {
                // 已有权限，可以显示悬浮窗
                isShow = true
            }

        }else{
            isShow = true
        }
        return isShow
    }
    @JvmStatic
    fun pixl0Alive(): Boolean {
        //===
        val isShow = show0Pixl()
        if (!isShow){
            AliveUtils.toast(msg = appContext.getString(R.string.quanxian34))
            return isShow
        }
        val keepAliveByFloatingWindow = !AliveUtils.getKeepAliveByFloatingWindow()
        AliveUtils.setKeepAliveByFloatingWindow(keepAliveByFloatingWindow)
        AliveUtils.requestUpdateKeepAliveByFloatingWindow(keepAliveByFloatingWindow)
        AliveUtils.toast(appContext, if (keepAliveByFloatingWindow) appContext.getString(R.string.quanxian0xiangsu) else appContext.getString(R.string.quanxian13))
        //===
        return isShow
    }
    @JvmOverloads
    @JvmStatic
    fun backendTaskHide(list: Collection<String> = emptyList()) {
        //===
        val keepAliveByTaskHide = !AliveUtils.getKeepAliveByTaskHide()
        AliveUtils.setKeepAliveByTaskHide(keepAliveByTaskHide)
        AliveUtils.requestUpdateKeepAliveByTaskHide(keepAliveByTaskHide,list)
        AliveUtils.toast(appContext, if (keepAliveByTaskHide) appContext.getString(R.string.quanxiantaskhide) else appContext.getString(R.string.quanxian13))
        //===

    }
    @JvmOverloads
    @JvmStatic
    fun backendTaskHidePlus(list: Collection<String> = emptyList()) {
        //===
        val keepAliveByTaskHidePlus = !AliveUtils.getKeepAliveByTaskHidePlus()
        AliveUtils.setKeepAliveByTaskHidePlus(keepAliveByTaskHidePlus)
        //执行后,会立即finish所有的activity,包括当前,故,只在软件处于后台运行时,才调用
        //AliveUtils.requestUpdateKeepAliveByTaskHidePlus(keepAliveByTaskHidePlus,list)
        AliveUtils.toast(appContext, if (keepAliveByTaskHidePlus) appContext.getString(R.string.quanxiantaskhidePlus) else appContext.getString(R.string.quanxian13))
        //===

    }
    @JvmOverloads
    @JvmStatic
    fun shouxianzhi(ctx: Context = appContext) {
        ctx ?: return
        val intent = Intent()
        intent.setAction("android.intent.action.VIEW")
        val content_url = Uri.parse("https://mp.weixin.qq.com/s/CbRFGUrqoKJie3JTdRmWPA")
        intent.setData(content_url)
        // 添加 FLAG_ACTIVITY_NEW_TASK 标志以确保能从 application context 启动
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
    @JvmOverloads
    @JvmStatic
    fun openAdmin(fragment: Fragment?=null,activity: Activity,ctx: Context = appContext,imageView: ImageView?,ic_open: Int=R.drawable.ic_open_xpq,ic_close: Int=R.drawable.ic_close_xpq) {
        ctx ?: return
        val drawableYes = ContextCompat.getDrawable(ctx, ic_open)
        val drawableNo =  ContextCompat.getDrawable(ctx, ic_close)
        val firstInstallTime = AliveUtils.getFirstInstallTime(ctx)
        val yuDay = 30 - (System.currentTimeMillis() - firstInstallTime!!) / (24 * 60 * 60 * 1000L)
        val msg: String = if (0 <= yuDay && yuDay <= 30) {
            String.format(Locale.ROOT, ctx.getString(R.string.quanxianguanliyuan), yuDay)
        } else {
            ctx.getString(R.string.quanxianguanliyuan1)
        }

        val normalDialog = AlertDialog.Builder(activity)
        //                normalDialog.setIcon(R.drawable.ic_float_app);
        normalDialog.setTitle(ctx.getString(R.string.wenxintixing))
        normalDialog.setMessage(msg)
        normalDialog.setPositiveButton(ctx.getString(R.string.nimbleisopen)) { dialog, which ->
            // 0<=yuDay && yuDay<=30
            if (0<=yuDay && yuDay<=30) {
                AliveUtils.toast(ctx, "" + yuDay)
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
                if (fragment==null){
                    val easyPermission = AliveUtils.easyRequestPermission(activity, PermissionLists.getBindDeviceAdminPermission(
                        MyDeviceAdminReceiverXpq::class.java),"设备管理员")
                    if (easyPermission) {
                        imageView?.setImageDrawable(drawableYes)
                    } else {
                        imageView?.setImageDrawable(drawableNo)
                    }
                }else{
                    val easyPermission = AliveUtils.easyRequestPermission(fragment, PermissionLists.getBindDeviceAdminPermission(
                        MyDeviceAdminReceiverXpq::class.java),"设备管理员")
                    if (easyPermission) {
                        imageView?.setImageDrawable(drawableYes)
                    } else {
                        imageView?.setImageDrawable(drawableNo)
                    }
                }

                //===
            }
        }
        normalDialog.setNegativeButton(ctx.getString(R.string.cancel)) { dialog, which ->
            //...To-do
            cancelAdmin()
            imageView?.setImageDrawable(drawableNo)


        }

        normalDialog.setNeutralButton(ctx.getString(R.string.sxzxpq)) { dialog, which ->
            //...To-do
            shouxianzhi()


        }
        // 显示
        normalDialog.show()
    }
    @JvmOverloads
    @JvmStatic
    fun cancelAdmin(ctx: Context = appContext) {
        if (ctx==null)return
        val devAdminReceiver = ComponentName(ctx, MyDeviceAdminReceiverXpq::class.java)
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (devAdminReceiver==null)return
        if (dpm==null)return
        if (dpm.isAdminActive(devAdminReceiver)) {
            dpm.removeActiveAdmin(devAdminReceiver)
            AliveUtils.toast(ctx, ctx.getString(R.string.quanxian13))
        } else {
            AliveUtils.toast(ctx, ctx.getString(R.string.quanxian12))
        }
    }
    @JvmOverloads
    @JvmStatic
    fun deleteSelf(ctx: Context = appContext) {
        if (ctx==null)return
        // 创建卸载Intent
        val intent = Intent(Intent.ACTION_DELETE)
        intent.data = Uri.parse("package:"+ctx.packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 执行卸载Intent
        ctx.startActivity(intent)
    }
    @JvmOverloads
    @JvmStatic
    fun openSettingAdmin(context: Context = appContext) {
        val componentName = ComponentName(context, MyDeviceAdminReceiverXpq::class.java)
        val componentNameSettings = ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "管理员保活")
            setComponent(componentNameSettings)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val resolveInfo = context.getPackageManager()
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfo != null) {
            context.startActivity(intent)
        } else {
            AliveUtils.toast(msg = "请去设置中手动开启")
        }



    }
    @JvmOverloads
    @JvmStatic
    fun openSettingFloat(context: Context = appContext) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            setData(Uri.parse("package:" + appContext.getPackageName()))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val resolveInfo = context.getPackageManager()
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfo != null) {
            context.startActivity(intent)
        } else {
            AliveUtils.toast(msg = "请去设置中手动开启")
        }



    }
    @JvmOverloads
    @JvmStatic
    fun setForgrountDialog(activity: Activity,ctx: Context = appContext,serviceClass: Class<out NotificationListenerService>?,imageView: ImageView?,ic_open: Int=R.drawable.ic_open_xpq,ic_close: Int=R.drawable.ic_close_xpq) {
        ctx ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            // 检查Android14前台服务权限
            val permissionCheck = ContextCompat.checkSelfPermission(ctx, Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        //===
        if (NotificationUtilXpq.isNotificationEnabled()){
            //设置通知标题内容对话框
            //showCustomizeDialog()
            AliveUtils.showForgrountDialog(
                activity,
                ctx,
                serviceClass,
                imageView,
                ic_open,
                ic_close
            )
        }else{
            val easyPermission = AliveUtils.easyRequestPermission(activity, PermissionLists.getPostNotificationsPermission(),"发送通知")
            if (easyPermission) {
                AliveUtils.showForgrountDialog(
                    activity,
                    ctx,
                    serviceClass,
                    imageView,
                    ic_open,
                    ic_close
                )
            }
        }

    }
    @JvmStatic
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
    @JvmStatic
    fun showForgrountDialog(activity: Activity,ctx: Context = appContext,serviceClass: Class<out NotificationListenerService>?,imageView: ImageView?,ic_open: Int=R.drawable.ic_open_xpq,ic_close: Int=R.drawable.ic_close_xpq) {
        /* @setView 装入自定义View ==> R.layout.dialog_customize
         */
        ctx ?: return
        val drawableYes = ContextCompat.getDrawable(ctx, ic_open)
        val drawableNo =  ContextCompat.getDrawable(ctx, ic_close)
        val customizeDialog = AlertDialog.Builder(activity)?: return
        val dialogBinding = ForgroundserviceDialogXpqBinding.inflate(LayoutInflater.from(activity))

        // 获取EditView中的输入内容
        dialogBinding.dialogEditTitle.setText(MMKVUtil.get(MMKVConst.FORGROUNDSERVICETITLE, ""))
        dialogBinding.dialogEditContent.setText(MMKVUtil.get(MMKVConst.FORGROUNDSERVICECONTENT, ""))

        //重启自动开启前台服务 自动通知栏保活开关
        dialogBinding.autobaohuo.setOnClickListener {
            val isChecked = dialogBinding.autobaohuo.isChecked
            SPUtils.putBoolean(MMKVConst.AUTOBAOHUOISON, isChecked)
            AliveUtils.setKeepAliveByNotification(isChecked)
        }

        dialogBinding.autobaohuo.isChecked = AliveUtils.getKeepAliveByNotification()

        //自动清除通知栏保活的通知
        dialogBinding.clearautobaohuo.setOnClickListener {
            val isChecked = dialogBinding.clearautobaohuo.isChecked
            AliveUtils.setAC_AliveNotification(isChecked)
            if (isChecked){
                dialogBinding.readnotificationbarView.visibility = View.VISIBLE
            }else{
                dialogBinding.readnotificationbarView.visibility = View.GONE
            }
        }

        dialogBinding.clearautobaohuo.isChecked = AliveUtils.getAC_AliveNotification()
        //开启读取通知栏权限
        dialogBinding.getnotificationSwitch.setOnClickListener {
            val isChecked = dialogBinding.getnotificationSwitch.isChecked
            if (!isChecked)return@setOnClickListener
            var b = false
            if (serviceClass!= null){
                b = isNotificationListenerEnabled(appContext, serviceClass)
            }else{
                if (isServiceDeclared(ctx, ClearNotificationListenerServiceImp::class.java)) {
                    b = isNotificationListenerEnabled(appContext, ClearNotificationListenerServiceImp::class.java)
                }else{
                    b = false
                }
            }
            if(b){
                AliveUtils.toast(ctx, ctx.getString(R.string.quanxianreadNoti))
                return@setOnClickListener
            }
            val tipDialog = AlertDialog.Builder(activity)

            tipDialog
                .setMessage(ctx.getString(R.string.quanxian281))
                .setPositiveButton(ctx.getString(R.string.ok)) { _, _ ->
                    if (serviceClass!= null){
                        AliveUtils.openNotificationListener(activity, serviceClass!!)
                    }else{
                        if (isServiceDeclared(ctx, ClearNotificationListenerServiceImp::class.java)) {
                            AliveUtils.openNotificationListener(activity, ClearNotificationListenerServiceImp::class.java)
                        }else{
                            NotificationUtilXpq.gotoNotificationAccessSetting()
                        }
                    }
                }
                .setNegativeButton(ctx.getString(R.string.cancel)) { _, _ ->
                    AliveUtils.toast(ctx, ctx.getString(R.string.cancel))
                    dialogBinding.getnotificationSwitch.isChecked =false
                }
                .setNeutralButton(ctx.getString(R.string.sxzxpq)){_, _ ->
                    dialogBinding.getnotificationSwitch.isChecked = false
                    shouxianzhi()
                }
                .show()





        }
        dialogBinding.getnotificationSwitch.isChecked = if (serviceClass!= null){
            isNotificationListenerEnabled(appContext, serviceClass!!)
        }else{
            if (isServiceDeclared(ctx, ClearNotificationListenerServiceImp::class.java)) {
                isNotificationListenerEnabled(appContext, ClearNotificationListenerServiceImp::class.java)
            }else{
                NotificationUtilXpq.isNotificationListenersEnabled()
            }
        }
            



        if (dialogBinding.clearautobaohuo.isChecked) {
            dialogBinding.readnotificationbarView.visibility = View.VISIBLE

        } else {
            dialogBinding.readnotificationbarView.visibility = View.GONE
        }

        customizeDialog.setTitle(ctx.getString(R.string.quanxian9))
        customizeDialog.setView(dialogBinding.root)

        //确定按钮
        customizeDialog.setPositiveButton(ctx.getString(R.string.ok)) { dialog, which ->
            val title = dialogBinding.dialogEditTitle.text.toString().trim { it <= ' ' }
            val content = dialogBinding.dialogEditContent.text.toString().trim { it <= ' ' }

            if (TextUtils.isEmpty(title) || TextUtils.isEmpty(content)) {
                MMKVUtil.put(MMKVConst.FORGROUNDSERVICETITLE, ctx.getString(R.string.wendingrun2))
                MMKVUtil.put(MMKVConst.FORGROUNDSERVICECONTENT, ctx.getString(R.string.wendingrun4))
            } else {
                MMKVUtil.put(MMKVConst.FORGROUNDSERVICETITLE, title)
                MMKVUtil.put(MMKVConst.FORGROUNDSERVICECONTENT, content)
            }

            AliveUtils.toast(ctx, MMKVUtil.get(MMKVConst.FORGROUNDSERVICETITLE, ctx.getString(R.string.wendingrun2)))
            //===
            //启动服务
            AliveUtils.startFGAlive(enable = true)
            //===
//            AliveUtils.setKeepAliveByNotification(true)
            imageView?.setImageDrawable(drawableYes)
        }

        //取消按钮
        customizeDialog.setNegativeButton(ctx.getString(R.string.quanxian14)) { dialog, which ->
            //==
            if (!fgs_ison) {
                AliveUtils.toast(ctx, ctx.getString(R.string.quanxian12))
            } else {
                //停止服务 这将触发服务的 onDestroy() 方法，释放资源并关闭前台通知
                AliveUtils.startFGAlive(enable = false)
            }
            //==
//            AliveUtils.setKeepAliveByNotification(false)
            imageView?.setImageDrawable(drawableNo)
        }
        customizeDialog.show()
    }
    @JvmStatic
    fun getHideList(): MutableList<String?> {
        val hideList: MutableList<String?> = ArrayList<String?>()
        //hideList.add(LaunchActivity::class.java.getName())
        return hideList
    }

    @JvmOverloads
    @JvmStatic
    fun closeTaskHidePlus(imageViewPlus: ImageView? = null,ic_close: Int =R.drawable.ic_close_xpq){
        //跳转界面时,避免因移除任务栈导致的闪退现象
        //关闭后台隐藏plus
        if (AliveUtils.getKeepAliveByTaskHidePlus()){
            AliveUtils.setKeepAliveByTaskHidePlus(false)
            imageViewPlus?.setImageResource(ic_close)
            if (appContext==null)return
            AliveUtils.toast(appContext, appContext.getString(R.string.closehideplus))

        }

    }

    @JvmOverloads
    @JvmStatic
    fun setTempPermissionValue(value: Boolean = true){
        //默认值为false 即 跳转界面时,避免因移除任务栈导致的闪退现象
        XpqBootReceiver.permissionTempValue = value
    }

    @JvmOverloads
    @JvmStatic
    fun getTempPermissionValue(): Boolean{
        //默认值为false 即 跳转界面时,避免因移除任务栈导致的闪退现象
        return XpqBootReceiver.permissionTempValue
    }

    @JvmStatic
    fun finishAndRemoveTask(activity: Activity?){
        activity?.finishAndRemoveTask()
    }


}