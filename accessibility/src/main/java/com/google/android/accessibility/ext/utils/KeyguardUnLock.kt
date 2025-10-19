package com.google.android.accessibility.ext.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.KeyguardManager.OnKeyguardExitResult
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.IntRange
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.ext.window.LogWrapper
import com.google.android.accessibility.selecttospeak.accessibilityService
import java.util.Locale

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/10/15  10:38
 * Description:This is KeyguardUnLock
 */

// 表示设备锁定与安全配置的组合状态
sealed class DeviceLockState {
    // 设备已解锁（可能设置了安全锁，也可能未设置）
    data class Unlocked(val isDeviceSecure: Boolean) : DeviceLockState()

    // 设备当前被锁，但没有安全锁（例如滑动解锁） —— 可直接解除
    object LockedNotSecure : DeviceLockState()

    // 设备当前被锁，并且设置了安全锁（PIN/图案/密码/生物） —— 需要用户验证
    object LockedSecure : DeviceLockState()
}
object KeyguardUnLock {

    /**
     * 根据 KeyguardManager 的两个 API 判断当前设备状态，返回 DeviceLockState。
     *
     * 使用场景：
     *  - 如果返回 Unlocked(isDeviceSecure = true) 表示设备已解锁，但用户设置了安全锁（已验证）。
     *  - 如果 LockedNotSecure：设备被锁但无安全锁，可直接解除/继续敏感操作。
     *  - 如果 LockedSecure：设备被锁且有安全保护，需要调用 requestDismissKeyguard() 或引导用户验证。
     */
    @JvmOverloads
    @JvmStatic
    fun getDeviceLockState(byKeyguard: Boolean = true): DeviceLockState {
        val km = appContext.getSystemService(KeyguardManager::class.java)
            ?: return DeviceLockState.Unlocked(isDeviceSecure = false) // 保守默认：当无法获取时当作未配置安全锁并解锁

        val deviceSecure = km.isDeviceSecure  // 设备是否配置了 PIN/Pattern/密码/生物 等
        val deviceLocked = if (!byKeyguard) {
            km.isDeviceLocked   // 当前设备是否处于“锁定”状态（需要验证才能访问用户数据）
        } else {
            km.isKeyguardLocked
        }
        return when {
            // 设备没有被锁（可直接使用），无论是否配置安全锁
            !deviceLocked -> DeviceLockState.Unlocked(isDeviceSecure = deviceSecure)

            // 设备被锁并且没有配置安全锁（例如只有滑动解锁） -> 可以自动解除（系统可以直接 dismiss）
            deviceLocked && !deviceSecure -> DeviceLockState.LockedNotSecure

            // 设备被锁且配置了安全锁 -> 需要用户验证
            deviceLocked && deviceSecure -> DeviceLockState.LockedSecure

            else -> DeviceLockState.Unlocked(isDeviceSecure = deviceSecure) // 保守兜底
        }
    }
    /**
     * 设置 Activity 在锁屏时显示，并点亮屏幕。
     * @param activity 需要操作的 Activity 实例。
     * 从Android 12开始，setShowWhenLocked(true) 的行为受到了限制
     * 如果你的应用需要在后台启动一个在锁屏上显示的 Activity（例如来电界面、闹钟），
     * 你必须在 AndroidManifest.xml 中声明 USE_FULL_SCREEN_INTENT 权限，
     * 并使用与全屏 Intent 相关的通知
     * <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
     */
    @JvmStatic
    fun showWhenLockedAndTurnScreenOn(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            //第一步:点亮屏幕 显示内容
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                //activity.setDismissKeyguard(true)
            }
            sendLog("设备系统大于8.1  点亮屏幕")
            //第二步:解锁
            requestDeviceUnlock(activity)

        } else {

            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
            sendLog("设备系统小于8.1  点亮屏幕")
        }
    }

    /**
     * 8.0下还需要清单文件中申请权限
     * 系统只会解除非安全锁屏（例如滑动解锁）。
     * 如果设备有密码、PIN 或生物识别锁，用户仍需手动解锁
     * */
    @SuppressLint("MissingPermission")
    @JvmStatic
    fun requestDeviceUnlock(activity: Activity) {

        val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        keyguardManager?: return


        // 检查设备是否设置了安全锁屏
        if (keyguardManager.isDeviceSecure) {
            /**
             * 从 Android O (API 26) 开始，应使用 KeyguardManager.requestDismissKeyguard()。
             * 它会向用户展示解锁界面（PIN、图案或指纹），并通过 KeyguardDismissCallback 返回结果。
             * 系统只会解除非安全锁屏（例如滑动解锁）。
             *
             * 如果设备有密码、PIN 或生物识别锁，用户仍需手动解锁。
             * */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                //不需要关注 回调的话 传null
                keyguardManager.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        super.onDismissSucceeded()
                        // 解锁成功，执行需要安全验证的操作
                        sendLog("解锁成功")
                        AliveUtils.toast(msg = "解锁成功")
                        // 例如：跳转到应用的敏感部分
                    }

                    override fun onDismissCancelled() {
                        super.onDismissCancelled()
                        // 用户取消了解锁（例如按了返回键）
                        sendLog("解锁被取消")
                        AliveUtils.toast(msg = "解锁被取消")
                    }

                    override fun onDismissError() {
                        super.onDismissError()
                        // 发生错误，无法显示解锁界面
                        // 常见原因：Activity 不可见或没有设置 setShowWhenLocked(true)
                        AliveUtils.toast(msg = "解锁出错")
                        sendLog("解锁出错")
                    }
                })
            }else{
                // 旧的回调接口
                @Suppress("DEPRECATION")
                keyguardManager.exitKeyguardSecurely(object : OnKeyguardExitResult {
                    override fun onKeyguardExitResult(success: Boolean) {
                        if (success) {
                            // 解锁成功
                            sendLog("解锁成功")
                            AliveUtils.toast(msg = "解锁成功")
                        } else {
                            // 解锁失败
                            sendLog("解锁出错")
                            AliveUtils.toast(msg = "解锁出错")
                        }
                    }
                });



            }
        } else {
            // 没有安全锁，直接执行操作
            sendLog("设备未设置安全锁,不需要解锁")
            //AliveUtils.toast(msg = "设备未设置安全锁")
        }
    }

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
        val className_lower = className.lowercase()
        val viewIdName = nodeInfo.viewIdResourceName ?: ""
        val viewIdName_lower = viewIdName.lowercase()

        // 检查是否为目标TextView节点
        if (
            (
                    className_lower.contains("text")||
                    className_lower.contains("button")||
                    className_lower.contains("chip")
            )&&
            viewIdName_lower.contains("id")&&
            (
                    viewIdName_lower.contains("digit")||
                    viewIdName_lower.contains("number")
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
    /**
     * 其它都一样,只有 锁屏上弹出“闹钟界面”  这个时候 再进行判定
     * true（设备仍锁定isDeviceLocked）	false（Keyguard 被临时隐藏）
     * */
    @JvmOverloads
    @JvmStatic
    fun deviceIsOn(context: Context = appContext): Boolean {
        var isDeviceOn = false
        val mKeyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (mKeyguardManager.isDeviceLocked){
            //设备锁定,需要解锁
            isDeviceOn = false
        }else{
            //设备没锁定,不需要解锁
            isDeviceOn = true
        }
        return isDeviceOn
    }
    @JvmOverloads
    @JvmStatic
    fun unlockScreen(access_Service: AccessibilityService? = accessibilityService, password: String=""): Boolean {
        var    isSuc = false
        if (access_Service == null) {
            sendLog("无障碍服务未开启!")
            return isSuc
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            sendLog("系统版本小于7.0")
            return isSuc
        }
        //val password = MMKVUtil.get(MMKVConst.KEY_LOCK_SCREEN_PASSWORD, "")
        if (TextUtils.isEmpty(password)) {
            sendLog("解锁密码为空,请先输入密码")
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
                //return
            }
            Log.e("解锁", "第三步:获得解锁界面节点id= "+digitId)
            sendLog("第三步:获得解锁界面节点id= "+digitId)
            //====================

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
                    falseCount++
                    val i = num++
                    Log.e("解锁", "第五.一步,输入密码"+s+"失败"+"第"+ i+"次输入")
                    sendLog("第五.一步,输入密码"+s+"失败"+"第"+ i +"次输入")
                } else {
                    trueCount++
                    val i = num++
                    Log.e("解锁", "第五.一步,输入密码"+s+"成功"+"第"+ i+"次输入")
                    sendLog("第五.一步,输入密码"+s+"成功"+"第"+ i +"次输入")
                }
            }
            Log.e("解锁", "第五.二步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")
            sendLog("第五.二步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")

            if (falseCount == 0){
                isSuc = true
                sendLog("第六步,所有密码输入成功")
                Log.e("解锁", "第六步,所有密码输入成功")
            }else{
                isSuc = false
                sendLog("第六步,所有密码输入失败")
                Log.e("解锁", "第六步,所有密码输入失败")
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
                    //睡眠一下 等待 解锁界面加载出来
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
    @JvmOverloads
    @JvmStatic
    fun unlockScreenNew(access_Service: AccessibilityService? = accessibilityService, password: String=""): Boolean {
        var    isSuc = false
        if (access_Service == null) {
            sendLog("无障碍服务未开启!")
            return isSuc
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            sendLog("系统版本小于7.0")
            return isSuc
        }
        //val password = MMKVUtil.get(MMKVConst.KEY_LOCK_SCREEN_PASSWORD, "")
        if (TextUtils.isEmpty(password)) {
            sendLog("解锁密码为空,请先输入密码")
            return isSuc
        }
        //===============
        fun jiesuoRun(digitId: String) {
            //====================
            if (TextUtils.equals("0",password)|| (password.length>0 && password.length<4)){
                //return
            }
            Log.e("解锁", "第1步:获得解锁界面节点id= "+digitId)
            sendLog("第1步:获得解锁界面节点id= "+digitId)
            //====================
            //3.模拟锁屏密码输入完成解锁
            Log.e("解锁", "第2步,准备遍历并输入保存的密码= "+password)
            sendLog("第2步,准备遍历并输入保存的密码= "+password)
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
                    falseCount++
                    val i = num++
                    Log.e("解锁", "第3.1步,输入密码"+s+"失败"+"第"+ i+"次输入")
                    sendLog("第3.1步,输入密码"+s+"失败"+"第"+ i +"次输入")
                } else {
                    trueCount++
                    val i = num++
                    Log.e("解锁", "第3.1步,输入密码"+s+"成功"+"第"+ i+"次输入")
                    sendLog("第3.1步,输入密码"+s+"成功"+"第"+ i +"次输入")
                }
            }
            Log.e("解锁", "第3.2步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")
            sendLog("第3.2步,输入密码: 成功次数=$trueCount, 失败次数=$falseCount")

            if (falseCount == 0){
                isSuc = true
                sendLog("第4步,所有密码输入成功")
                Log.e("解锁", "第4步,解锁成功")
            }else{
                isSuc = false
                sendLog("第4步,所有密码输入失败")
                Log.e("解锁", "第4步,解锁失败")
            }

        }


        //===================
        jiesuoRun(getLockViewID(access_Service.rootInActiveWindow))
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
    fun performClickNodeInfo(
        service: AccessibilityService,
        nodeInfo: AccessibilityNodeInfo?
    ): Boolean {
        if (nodeInfo == null) return false

        try {
            // 1️⃣ 当前节点可点击
            if (nodeInfo.isClickable) {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // 模拟真实点击（绕过部分系统限制）
                    val rect = Rect()
                    nodeInfo.getBoundsInScreen(rect)
                    if (rect.centerX() > 0 && rect.centerY() > 0) {
                        moniClick(rect.centerX(), rect.centerY(), service)
                        true
                    } else {
                        false
                    }
                } else {
                    nodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }

            // 2️⃣ 当前节点不可点击，则尝试父节点
            val parent = nodeInfo.parent
            if (parent != null) {
                val clicked = performClickNodeInfo(service, parent)

                // ⚠️ 避免 Android 14+ 因 "recycled object" 崩溃
                if (Build.VERSION.SDK_INT < 34) {
                    try {
                        parent.recycle()
                    } catch (_: Throwable) {
                    }
                }

                return clicked
            }

        } catch (t: Throwable) {
            // 避免部分设备 AccessibilityNodeInfo 异常崩溃
            t.printStackTrace()
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
        if (TextUtils.isEmpty(id) && TextUtils.isEmpty(text)) {
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
        try {
            val path = Path()
            path.moveTo(X.toFloat(), Y.toFloat())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val builder = GestureDescription.Builder().addStroke(StrokeDescription(path, 0, MMKVConst.clickDu_Time))
                return service.dispatchGesture(builder.build(), null, null)
            } else {
                return false
            }
        } catch (e: Exception) {
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