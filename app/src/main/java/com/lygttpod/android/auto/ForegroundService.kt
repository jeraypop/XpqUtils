package com.lygttpod.android.auto

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import xpq.friend.R


/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2022/7/15 0015  9:25
 * Description:This is ForegroundService
 */
class ForegroundService : Service() {

    companion object{
        @JvmField
        var mForegroundService:Intent?=null
        private const val TAG = "ForegroundService"
        @JvmField
        var serviceIsLive: Boolean = false
        private const val NOTIFICATION_ID = 1701
        //唯一的通知通道的ID
        private const val notificationChannelId = "notification_c_id_01"

    }
    var notificationManager: NotificationManager? = null
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        Log.d(TAG,"OnCreate")
        startForegroundWithNotification()

    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG,"onBind")
        return null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG,"onUnbind")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG,"onStartCommand")
        serviceIsLive=true
        //数据获取
        val data: String? = intent?.getStringExtra("Foreground") ?: "正在开启,请稍等"
//        if (MyApplication.spwang?.getBoolean("autobaohuoison", false) == false){
//            Toast.makeText(this, data, Toast.LENGTH_SHORT).show()
//        }

        return  START_STICKY
    }

    /**
     * 开启前景服务并发送通知
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startForegroundWithNotification(){
//        val notificationManager = getSystemService(NotificationManager::class.java)
        //8.0及以上注册通知渠道
        createNotificationChannel()
        val notification: Notification = createForegroundNotification()
        //将服务置于启动状态 ,NOTIFICATION_ID指的是创建的通知的ID
         //33
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                //发送通知到状态栏
                notificationManager?.notify(NOTIFICATION_ID, notification);

            }

        }else{
            //发送通知到状态栏
            notificationManager?.notify(NOTIFICATION_ID, notification);
        }



        
        // api >= 34
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        }
        // api <= 33
        else {
            startForeground(NOTIFICATION_ID, notification)
        }





    }


    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel(){
//        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        val notificationManager = getSystemService(NotificationManager::class.java)
        //Android8.0以上的系统，新建消息通道
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            //用户可见的通道名称
            val channelName: String = "Foreground Service Notification"
            //通道的重要程度
            val importance: Int = NotificationManager.IMPORTANCE_HIGH
            //构建通知渠道
            val notificationChannel: NotificationChannel
            = NotificationChannel(notificationChannelId,
                channelName, importance) .apply {
                description = "Channel description"
            }
//            notificationChannel.description = "Channel description"
            //LED灯
            notificationChannel.enableLights(true)
            notificationChannel.lightColor = Color.RED
            //震动
            notificationChannel.vibrationPattern = longArrayOf(0,1000,500,1000)
            notificationChannel.enableVibration(true)
            //向系统注册通知渠道，注册后不能改变重要性以及其他通知行为
            notificationManager?.createNotificationChannel(notificationChannel)
        }
    }

    /**
     * 创建服务通知
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createForegroundNotification(): Notification {

//        val builder: NotificationCompat.Builder = NotificationCompat.Builder(applicationContext, notificationChannelId)

        val builder = Notification.Builder(this,notificationChannelId)

        //通知小图标
        builder.setSmallIcon(R.mipmap.ic_launcher)
        //通知标题
        builder.setContentTitle("保证后台稳定运行通知")
        //通知内容
        builder.setContentText("检测完毕,即可关闭")
        //设置通知显示的时间
        builder.setWhen(System.currentTimeMillis())
        //设定启动的内容
        val  activityIntent: Intent = Intent(this, MainActivity::class.java)
        activityIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

//        val pendingIntent: PendingIntent = PendingIntent.getActivity(this,
//            1,activityIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val pendingIntent: PendingIntent
        pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)

        builder.setContentIntent(pendingIntent)
//        builder.priority = NotificationCompat.PRIORITY_DEFAULT
        //设置为进行中的通知
        builder.setOngoing(true)
        //创建通知并返回
        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopForeground(true)
        ForegroundService.serviceIsLive = false;
    }





}