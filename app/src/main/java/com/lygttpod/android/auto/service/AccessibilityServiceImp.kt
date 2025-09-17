package com.lygttpod.android.auto.service

//import com.assistant.`fun`.redbwx.utils.MyUtilsKotlin.zhuli_youSkipAD
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.utils.AliveUtils
import com.google.android.accessibility.ext.utils.AliveUtils.toast
import com.google.android.accessibility.notification.AccessibilityNInfo

/**
 * 第二次继承
 *
 *
 *  WXAccessibility
 *
 */
open class AccessibilityServiceImp : FirstAccessibility() {

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return Service.START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)


    }

    override fun onUnbind(intent: Intent?): Boolean {

        return super.onUnbind(intent)
    }
    override fun onInterrupt() {




    }
    @SuppressLint("SuspiciousIndentation")
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onServiceConnected() {
        super.onServiceConnected()
        toast(applicationContext,"无障碍服务已连接")
        //=========================

    }



    @RequiresApi(Build.VERSION_CODES.N)
    override fun asyncHandleAccessibilityEvent(accessibilityEvent: AccessibilityEvent) {
        if (accessibilityEvent==null)return

    }

    override fun asyncHandleAccessibilityNotification(notification: Notification, title: String, content: String, a_n_Info: AccessibilityNInfo){
        val pi = a_n_Info.pi
//        AliveUtils.piSend( pi)
        // 统一日志输出，便于对比
        Log.e("辅助服务通知", "===============================\n" +
                "来自应用: ${a_n_Info.pkgName} = ${a_n_Info.appName}\n" +
                "方式一 (event.text): ${a_n_Info.eventText}\n" +
                "方式二 (Notification): 标题=[${a_n_Info.title}] 内容=[${a_n_Info.content}]\n" +
                "===============================")

    }

    /**
     * 适合短时任务，父类会在 dealEvent 的 finally 回收副本。
     * 同步接收副本集合并处理（父类会在调用后回收这些副本）。
     * 这里不接管任何 node，返回后父类会回收 node。
     */
    override fun asyncHandle_WINDOW_STATE_CHANGED(
        root: AccessibilityNodeInfo,
        nodeInfoSet: MutableSet<AccessibilityNodeInfo>,
        pkgName: String,
        className: String
    ) {
        // run in executor thread (already on executor)
        try {
            for (node in nodeInfoSet) {
                // 读取必要字段（建议短小快）
                val id = node.viewIdResourceName
                val cls = node.className?.toString()
                val txt = node.text?.toString()?.take(200) // 限制长度
                Log.d("SyncWindowHandler", "node id=$id cls=$cls text=${txt ?: "null"}")
                // 例如：把结果存入 DB 或发事件（尽量短耗时）
            }
        } catch (t: Throwable) {
            Log.w("SyncWindowHandler", "handle error", t)
        }
        // 不接管（不调用 claim/submit），父类会自动回收 node 副本
    }



    override fun onDestroy() {
        super.onDestroy()
    }




}