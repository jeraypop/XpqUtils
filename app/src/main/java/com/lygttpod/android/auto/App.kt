package com.lygttpod.android.auto

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.accessibility.ext.activity.TaskByJieSuoHelper
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.NotificationUtilXpq
import com.google.android.accessibility.ext.utils.XpqUncaughtExceptionHandler
import com.lygttpod.android.auto.notification.MyJieSuoHelper
import com.lygttpod.android.auto.notification.NotificationListenerServiceImp


class App : Application() {

    companion object {
        private var instance: Application? = null
        fun instance() = instance!!
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        TaskByJieSuoHelper.setInstance(MyJieSuoHelper())
        //重新绑定服务
        NotificationUtilXpq.toggleNotificationListenerService(notificationCls = NotificationListenerServiceImp::class.java)

        // 监听进程生命周期，应用进入后台时隐藏
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // 应用切到前台（visible）
                    try {
                        //悬浮窗
                       /* val float = AliveUtils.hasOverlayPermission(appContext)
                        //设备管理员
                        val firstInstallTime = AliveUtils.getFirstInstallTime(appContext)
                        val yuDay = 30 - (System.currentTimeMillis() - firstInstallTime!!) / (24 * 60 * 60 * 1000L)
                        val admin = if (0<=yuDay && yuDay<=30) {
                            true //不会打开新界面,所以没关系
                        }else{
                            if (getSystemService<DevicePolicyManager>()?.isAdminActive(ComponentName(applicationContext, MyDeviceAdminReceiverXpq::class.java)) == true) {
                                true
                            }else{
                                false
                            }
                        }
                        //都成功开启后,才允许进行 后台隐藏
                       if (float && admin){
                           setTempPermissionValue(false)
                       }*/



                    } catch (e: Exception) {

                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // 应用即将失去焦点，不再处于完全前台
                    /**
                     *可能是弹出系统弹窗；
                     *
                     * 可能是用户切到多任务界面；
                     *
                     * 可能是打开了新的 App；
                     *
                     * 应用此时仍“前台可见”，但不是当前交互对象
                     *
                     *
                     * */
                    try {

                        Thread {
                            repeat(6) {
                                //if (!AliveUtils.getTempPermissionValue()){
                                    AliveUtils.requestUpdateKeepAliveByTaskHide(AliveUtils.getKeepAliveByTaskHide())
                                    AliveUtils.requestUpdateKeepAliveByTaskHidePlus(AliveUtils.getKeepAliveByTaskHidePlus())

                                    SystemClock.sleep(200)
                                //}

                            }
                        }.start()


                    } catch (e: Exception) {

                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // 应用切到后台（）
                    try {

                        Thread {
                            repeat(1) {

                                    AliveUtils.requestUpdateKeepAliveByTaskHide(AliveUtils.getKeepAliveByTaskHide())
                                    AliveUtils.requestUpdateKeepAliveByTaskHidePlus(AliveUtils.getKeepAliveByTaskHidePlus())

                            }
                        }.start()


                    } catch (e: Exception) {

                    }
                }

                else -> {
                    // 其他事件忽略（ON_STOP 等可按需处理）
                }
            }
        })
        //异常捕获
        XpqUncaughtExceptionHandler.getInstance(this).run()
    }
}