package com.google.android.accessibility.ext.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import kotlin.math.abs
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
* 核心功能:  负责时间防篡改逻辑
*
*
* ✔ 飞行模式绕不过
✔ 管家断网绕不过
✔ VPN 阻断绕不过
✔ DNS 劫持绕不过
✔ 系统回拨绕不过
✔ SP 篡改绕不过
✔ 重启绕不过
*
*
* */

object HYSJTimeSecurityManager {

    private const val SP_NAME = "hysj_time_secure"
    private const val KEY_DATA = "secure_data"
    private const val KEY_SIGN = "secure_sign"

    // 允许本地时间和可信时间最大误差 30 分钟
    private const val MAX_TIME_DRIFT = 30 * 60 * 1000L
    // 默认允许离线小时数
    private const val DEFAULT_OFFLINE_HOURS = 1L
    //可信网络时间
    private var trustedNetworkTime: Long = 0L
    //本地运行时间
    private var baseElapsedRealtime: Long = 0L
    private var lastSyncElapsedRealtime: Long = 0L

    private var sp: android.content.SharedPreferences? = null

    /**
     * 1️⃣ Application 中初始化
     */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context = appContext) {
        sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        loadFromSp(context)
    }

    /**
     * 2️⃣ 每次获取服务器时间后调用
     * 联网成功后调用
     * 传入服务器时间戳（毫秒）为了稳定性 最好传入 从百度,阿里获取到的时间
     *
     * 建议：
     * 登录成功时
     * 打开 App 时
     * 每 6~24 小时刷新一次
     *
     */
    @JvmStatic
    @JvmOverloads
    fun updateTrustedTime(context: Context = appContext, networkTimestamp: Long) {

        trustedNetworkTime = networkTimestamp
        baseElapsedRealtime = SystemClock.elapsedRealtime()
        lastSyncElapsedRealtime = baseElapsedRealtime

        val rawData =
            "$trustedNetworkTime|$baseElapsedRealtime|$lastSyncElapsedRealtime"
        val sign = generateHmac(context, rawData)

        sp?.edit()
            ?.putString(KEY_DATA, rawData)
            ?.putString(KEY_SIGN, sign)
            ?.apply()
    }

    /**
     * 获取可信当前时间
     * 核心算法：网络基准时间 + 真实经过时间
     */
    @JvmStatic
    @JvmOverloads
    fun getTrustedNow(context: Context = appContext): Long {

        if (trustedNetworkTime == 0L) {
            return System.currentTimeMillis()
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val passed = nowElapsed - baseElapsedRealtime

        return trustedNetworkTime + passed
    }

    /**
     * 3️⃣ 判断会员
     * 会员是否有效
     * expireTimestamp 必须是服务器下发的会员毫秒时间戳
     */
    @JvmStatic
    @JvmOverloads
    fun isKYSJValid(
        context: Context = appContext,
        expireTimestamp: Long,
        allowOfflineHours: Long = DEFAULT_OFFLINE_HOURS
    ): Boolean {

        // 1️⃣ SP校验
        if (!verifySp(context)) {
            clear()
            return false
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val offlineLimit = allowOfflineHours * 60 * 60 * 1000L
        val offlineTime = nowElapsed - lastSyncElapsedRealtime

        // 2️⃣ 快速路径：系统判断无网络
        if (!isNetworkConnected(context)) {
            if (offlineTime > offlineLimit) return false
        } else {
            // 3️⃣ 有网络也必须限制最长未同步时间
            if (offlineTime > offlineLimit) return false
        }

        // 4️⃣ 系统时间校验（防回拨）
        if (!isSystemTimeValid()) {
            return false
        }

        // 5️⃣ 判断是否过期
        return expireTimestamp > getTrustedNow()
    }

    /**
     * 判断是否有人为修改系统时间
     * 如果系统时间和可信时间差超过 30 分钟则判定异常
     * 如果网络时间和本地时间对比相差 30 分钟之内，则判定没有人为修改
     */
    @JvmStatic
    @JvmOverloads
    fun isSystemTimeValid(context: Context = appContext): Boolean {

        if (trustedNetworkTime == 0L) return true

        val trustedNow = getTrustedNow(context)
        val systemNow = System.currentTimeMillis()

        val drift = abs(trustedNow - systemNow)

        return drift <= MAX_TIME_DRIFT
    }

    // =============================
    // SP校验
    // =============================

    private fun verifySp(context: Context): Boolean {

        val rawData = sp?.getString(KEY_DATA, null) ?: return false
        val savedSign = sp?.getString(KEY_SIGN, null) ?: return false
        val realSign = generateHmac(context, rawData)

        return realSign == savedSign
    }

    private fun loadFromSp(context: Context) {

        if (!verifySp(context)) {
            clear()
            return
        }

        val rawData = sp?.getString(KEY_DATA, null) ?: return
        val parts = rawData.split("|")
        if (parts.size != 3) return

        trustedNetworkTime = parts[0].toLongOrNull() ?: 0L
        baseElapsedRealtime = parts[1].toLongOrNull() ?: 0L
        lastSyncElapsedRealtime = parts[2].toLongOrNull() ?: 0L
    }

    private fun generateHmac(context: Context, data: String): String {

        val secret = getDeviceSecret(context)

        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(keySpec)

        val hash = mac.doFinal(data.toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun getDeviceSecret(context: Context): String {

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val model = Build.MODEL ?: "unknown"

        return "VIP_SECRET_${androidId}_${model}_2025"
    }

    private fun isNetworkConnected(context: Context): Boolean {

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false

        val network = cm.activeNetwork ?: return false
        val capabilities =
            cm.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    }
    /**
     * 清除可信时间（可选）
     */
    fun clear() {
        trustedNetworkTime = 0L
        baseElapsedRealtime = 0L
        lastSyncElapsedRealtime = 0L
        sp?.edit()?.clear()?.apply()
    }
}
