package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.IntRange
import androidx.annotation.RequiresApi
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.window.LogWrapper
import com.google.android.accessibility.notification.AccessibilityNInfo
import com.google.android.accessibility.selecttospeak.accessibilityService
import java.util.Locale

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/10/15  10:38
 * Description:This is KeyguardUnLock
 */
object KeyguardUnLock {
    @JvmStatic
    fun getLockID(): String {
        val brand = Build.BRAND.uppercase(Locale.getDefault()) //获取设备品牌
        Log.e("解锁", "手机品牌 = " + brand);
        var id = "com.android.systemui:id/"
        when (brand) {
            "XIAOMI", "MIUI" -> {
                id = id + "digit_text"
            }

            "OPPO" -> {
                id = id + "oppo_digit_text"
            }

            "IQOO" -> {
                id = id + "iqoo_digit_text"
            }

            "VIVO" -> {
                id = id + "vivo_digit_text"
            }

            else -> {
                // 默认
                id = id + "digit_text"
            }
        }
        return id
    }

    @JvmStatic
    fun getLockViewID(nodeInfo: AccessibilityNodeInfo?): String {
        if (nodeInfo == null) {
            return getLockID()
        }

        return findLockView(nodeInfo) ?: getLockID()
    }

    private fun findLockView(nodeInfo: AccessibilityNodeInfo): String? {
        val className = nodeInfo.className?.toString() ?: ""
        val viewIdName = nodeInfo.viewIdResourceName ?: ""
        val viewIdName_lower = viewIdName.lowercase()

        // 检查是否为目标TextView节点
        if (className.lowercase().contains("textview") &&
            viewIdName_lower.contains(".systemui:id/")&&
            (viewIdName_lower.contains("digit_text")||
                    viewIdName_lower.contains("digittext")
             )
            ) {
            sendLog("本次查询解锁界面节点id= "+viewIdName)
            return viewIdName
        }

        // 递归遍历子节点
        for (i in 0 until nodeInfo.childCount) {
            nodeInfo.getChild(i)?.let { childNode ->
                val result = findLockView(childNode)
                if (result != null) {
                    return result
                }
            }
        }

        return null
    }
    @JvmOverloads
    @JvmStatic
    fun screenIsOn(context: Context = appContext): Boolean {
        var isScreenOn = false
        //是否需要亮屏唤醒屏幕
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            //屏幕黑屏需要唤醒
            isScreenOn = false
        }else{
          //屏幕是亮着的 不需要唤醒
            isScreenOn = true
        }
        return isScreenOn
    }
    @JvmOverloads
    @JvmStatic
    fun wakeScreenOn(context: Context = appContext): Boolean {
        var isScreenOn = false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            val wl = powerManager.newWakeLock(
                PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_DIM_WAKE_LOCK,
                context.packageName
            )
            wl.acquire(60 * 1000L /*1 minutes*/)
            wl.release()
            isScreenOn = true
        } catch (e: Exception) {
            isScreenOn = false
            //再试一次
            val wl = powerManager.newWakeLock(
                PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_DIM_WAKE_LOCK,
                context.packageName
            )
            wl.acquire(60 * 1000L /*1 minutes*/)
            wl.release()
        }
        return isScreenOn
    }

    @JvmOverloads
    @JvmStatic
    fun keyguardIsOn(context: Context = appContext): Boolean {
        var isKeyguardOn = false
        val mKeyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (mKeyguardManager.isKeyguardLocked){
            //键盘锁定,需要解锁
            isKeyguardOn = false
        }else{
            //键盘没锁定,不需要解锁
            isKeyguardOn = true
        }
        return isKeyguardOn
    }
    @JvmOverloads
    @JvmStatic
    fun unlockScreen(access_Service: AccessibilityService? = accessibilityService, password: String=""): Boolean {
        var    isSuc = false
        if (access_Service == null) {
            Log.e("解锁", "辅助服务为空")
            return isSuc
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return isSuc
        }
        //val password = MMKVUtil.get(MMKVConst.KEY_LOCK_SCREEN_PASSWORD, "")
        if (TextUtils.isEmpty(password)) {

            return isSuc
        }
        //1.获取设备的宽和高
        val screenSize = getScreenSize(appContext)
        val screenWidth = screenSize.first
        val screenHeight = screenSize.second

        sendLog("设备的宽度= "+screenWidth+", 高度= "+screenHeight)
        Log.e("解锁", "screenHeight" + screenHeight)
        Log.e("解锁", "screenWidth" + screenWidth)
        val y = screenHeight / 12 * 9f
        Log.e("解锁", "第一步,上划屏幕呼出输入解锁密码界面")
        sendLog("第一步,上划屏幕呼出输入解锁密码界面")
        //===============
        //1.向上滑动进入密码解锁界面
        val path = Path()
        path.moveTo(screenWidth / 8f, y)   //滑动起点
        path.lineTo(screenWidth / 8f, y - 800f)//滑动终点
        fun jiesuoRun(digitId: String) {
            //====================

            if (TextUtils.equals("0",password)|| (password.length>0 && password.length<4)){
                return
            }
            Log.e("解锁", "第三步:获得解锁界面节点id= "+digitId)
            sendLog("第三步:获得解锁界面节点id= "+digitId)
            //====================
            //睡眠一下 等待 解锁界面加载出来
            SystemClock.sleep(1500)
            //3.模拟锁屏密码输入完成解锁

            Log.e("解锁", "第四步,准备遍历并输入保存的密码= "+password)
            sendLog("第四步,准备遍历并输入保存的密码= "+password)
            var num=1
            fun inputMiMa(s: Char) =
                findAndPerformClickNodeInfo(
                    access_Service!!,
                    digitId,
                    s.toString(),
                    s.toString()
                )
            var trueCount = 0
            var falseCount = 0
            for (s in password!!) {
                val inputSuccess = inputMiMa(s)
                if (!inputSuccess) {
                    isSuc = false
                    falseCount++
                    val i = num++
                    Log.e("解锁", "第五.一步,输入密码"+s+"失败"+"第"+ i+"次输入")
                    sendLog("第五.一步,输入密码"+s+"失败"+"第"+ i +"次输入")
                } else {
                    isSuc = true
                    trueCount++
                    val i = num++
                    Log.e("解锁", "第五.一步,输入密码"+s+"成功"+"第"+ i+"次输入")
                    sendLog("第五.一步,输入密码"+s+"成功"+"第"+ i +"次输入")
                }
            }
            Log.e("解锁", "第五.二步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")
            sendLog("第五.二步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")

            if (trueCount == 4 && falseCount == 0){
                sendLog("第六步,解锁成功")
                Log.e("解锁", "第六步,解锁成功")
            }else{
                sendLog("第六步,解锁失败")
                Log.e("解锁", "第六步,解锁失败")
            }

            return
        }


        //===================
        move(
            access_Service!!,
            path,
            500, 500,
            object : Callback {
                override fun onSuccess() {
                    sendLog("第二步,手势上划成功,然后开始输入密码解锁")
                    Log.e("解锁", "第二步,手势上划成功,然后开始输入密码解锁")
                    SystemClock.sleep(1000)
                    jiesuoRun(getLockViewID(access_Service.rootInActiveWindow))
                    //===
                }

                //==============================================================
                override fun onError() {
                    sendLog("第二步,手势上划失败")
                    Log.e("解锁", "第二步,手势上划失败")
                }
            })
        return isSuc
    }
    @JvmStatic
    fun getScreenSize(context: Context): Pair<Int, Int> {
        val displayMetrics = context.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        return Pair(width, height)
    }


    /**
     * 滑动操作
     *
     * @param service   无障碍服务实例
     * @param path      移动路径
     * @param startTime 延时启动时间
     * @param duration  执行持续时间
     */

    @JvmStatic
    fun move(
        service: AccessibilityService,
        path: Path,
        @IntRange(from = 0) startTime: Long,
        @IntRange(from = 0) duration: Long,
        callback: Callback?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (callback == null) {
            service.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(StrokeDescription(path, startTime, duration)).build(),
                null,
                null
            )
        } else {
            service.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(StrokeDescription(path, startTime, duration)).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        super.onCompleted(gestureDescription)
                        callback.onSuccess()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        super.onCancelled(gestureDescription)
                        callback.onError()
                    }
                },
                null
            )
        }
    }

    /**
     * 查找并点击节点
     */

    @JvmStatic
    fun findAndPerformClickNodeInfo(
        service: AccessibilityService,
        id: String,
        text: String,
        contentDescription: String
    ): Boolean {
        return performClickNodeInfo(service,findNodeInfo(service, id, text, contentDescription))
    }

    /**
     * 点击节点
     *
     * @return true表示点击成功
     */

    @JvmStatic
    fun performClickNodeInfo(service: AccessibilityService,nodeInfo: AccessibilityNodeInfo?): Boolean {
        if (nodeInfo != null) {
            if (nodeInfo.isClickable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    //==模拟点击解锁
                    val rect = Rect()
                    nodeInfo.getBoundsInScreen(rect)
                    moniClick(rect.centerX(), rect.centerY() , service)
                } else {
                    nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                //==

                return true
            } else {
                val parent = nodeInfo.parent
                if (parent != null) {
                    val isParentClickSuccess = performClickNodeInfo(service,parent)
                    if (Build.VERSION.SDK_INT < 34) {
                        try {  parent.recycle() } catch (_: Throwable) { /* ignore */ }
                    }
                    return isParentClickSuccess
                }
            }
        }
        return false
    }

    /**
     * 查找节点信息
     *
     * @param id                 控件id  eg: com.tencent.mm:id/aqg
     * @param text               控件文本 eg: 打开
     * @param contentDescription 控件描述 eg: 表情
     * @return null表示未找到
     */
    @JvmStatic
    fun findNodeInfo(
        service: AccessibilityService,
        id: String,
        text: String,
        contentDescription: String
    ): AccessibilityNodeInfo? {
        if (TextUtils.isEmpty(text) && TextUtils.isEmpty(contentDescription)) {
            return null
        }
        SystemClock.sleep(500)
        val nodeInfo = service.rootInActiveWindow
        if (nodeInfo != null) {
            val list = nodeInfo
                .findAccessibilityNodeInfosByViewId(id)
            for (n in list) {
                val nodeInfoText =
                    if (TextUtils.isEmpty(n.text)) "" else n.text
                        .toString()
                val nodeContentDescription =
                    if (TextUtils.isEmpty(n.contentDescription)) "" else n.contentDescription
                        .toString()
                if (TextUtils.isEmpty(text)) {
                    if (contentDescription == nodeContentDescription) {
                        return n
                    }
                } else {
                    if (text == nodeInfoText) {
                        return n
                    }
                }
            }
        }
        return null
    }

    /**
     * 模拟
     * 点击
     */

    @JvmStatic
    fun moniClick(X: Int, Y: Int, service: AccessibilityService?): Boolean {
        if (service == null) {
            return false
        }
        val path = Path()
        path.moveTo(X.toFloat(), Y.toFloat())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val builder = GestureDescription.Builder().addStroke(StrokeDescription(path, 0, MMKVConst.clickDu_Time))
            return service.dispatchGesture(builder.build(), null, null)
        } else {
            return false
        }

    }

    @JvmStatic
    fun sendLog(sendText: String) {
        LogWrapper.logAppend(sendText)
    }


    private var lock: PowerManager.WakeLock? = null
    private var unLock: PowerManager.WakeLock? = null
    private var km: KeyguardManager? = null
    @Suppress("DEPRECATION")
    private var kl: KeyguardManager.KeyguardLock? = null

    @SuppressLint("InvalidWakeLockTag", "MissingPermission")
    @Suppress("DEPRECATION")
    @JvmOverloads
    @JvmStatic
    fun wakeUpAndUnlock(context: Context = appContext) {
        //屏锁管理器
        km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        kl = km!!.newKeyguardLock("unLock")
        //解锁
        kl!!.disableKeyguard()
        //获取电源管理器对象
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        //获取PowerManager.WakeLock对象,后面的参数|表示同时传入两个值,最后的是LogCat里用的Tag
        unLock = pm.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "bright"
        )
        //点亮屏幕
        unLock!!.acquire(1 * 1 * 66 * 1000L)
        sendLog("临时禁用锁屏")
        //        wl.acquire();
        //释放
        //        wl.release();
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @JvmStatic
    fun lockScreen() {
        kl?.let {
            // 锁屏
            it.reenableKeyguard()
            sendLog("重新恢复锁屏")
        }
        unLock?.let {
            // 释放wakeLock，关灯
            if (it.isHeld) {
                it.release()
            }
        }
    }



}
//---------------- 结果回调 ----------------
interface Callback {
    fun onSuccess()
    fun onError()
}