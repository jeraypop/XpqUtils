package com.google.android.accessibility.ext.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import kotlin.math.abs
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HYSJTimeSecurityManager {

    private const val SP_NAME = "hysj_time_secure"
    private const val KEY_DATA = "secure_data"
    private const val KEY_SIGN = "secure_sign"

    // 允许本地时间和可信时间最大误差 30 分钟
    private const val MAX_TIME_DRIFT = 30 * 60 * 1000L
    //可信网络时间
    private var trustedNetworkTime: Long = 0L
    //本地运行时间
    private var baseElapsedRealtime: Long = 0L

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

        val rawData = "$trustedNetworkTime|$baseElapsedRealtime"
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
     * 判断是否有人为修改系统时间
     * 如果系统时间和可信时间差超过 30 分钟则判定异常
     * 如果网络时间和本地时间对比相差 30 分钟之内，则判定没有人为修改
     */
    @JvmStatic
    @JvmOverloads
    fun isSystemTimeValid(context: Context = appContext): Boolean {

        if (!verifySp(context)) {
            clear()
            return false
        }

        if (trustedNetworkTime == 0L) return true

        val trustedNow = getTrustedNow(context)
        val systemNow = System.currentTimeMillis()

        val drift = abs(trustedNow - systemNow)

        return drift <= MAX_TIME_DRIFT
    }

    /**
     * 3️⃣ 判断会员
     * 会员是否有效
     * expireTimestamp 必须是服务器下发的会员毫秒时间戳
     */
    @JvmStatic
    @JvmOverloads
    fun isKYSJValid(context: Context = appContext, expireTimestamp: Long): Boolean {

        if (!isSystemTimeValid(context)) {
            return false
        }

        return expireTimestamp > getTrustedNow(context)
    }
    // ===============================
    // 校验SP是否被篡改
    // ===============================

    private fun verifySp(context: Context = appContext): Boolean {

        val rawData = sp?.getString(KEY_DATA, null) ?: return false
        val savedSign = sp?.getString(KEY_SIGN, null) ?: return false

        val realSign = generateHmac(context, rawData)

        if (realSign != savedSign) {
            return false
        }

        return true
    }

    // ===============================
    // 从SP恢复数据（带校验）
    // ===============================

    private fun loadFromSp(context: Context = appContext) {

        if (!verifySp(context)) {
            clear()
            return
        }

        val rawData = sp?.getString(KEY_DATA, null) ?: return

        val parts = rawData.split("|")
        if (parts.size != 2) return

        trustedNetworkTime = parts[0].toLongOrNull() ?: 0L
        baseElapsedRealtime = parts[1].toLongOrNull() ?: 0L
    }

    // ===============================
    // HMAC签名生成
    // ===============================

    private fun generateHmac(context: Context = appContext, data: String): String {

        val secret = getDeviceSecret(context)

        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(keySpec)

        val hash = mac.doFinal(data.toByteArray())

        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    // ===============================
    // 设备绑定密钥
    // ===============================

    private fun getDeviceSecret(context: Context = appContext): String {

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val model = Build.MODEL ?: "unknown"

        return "VIP_SECRET_${androidId}_${model}_2025"
    }

    /**
     * 清除可信时间（可选）
     */
    fun clear() {
        trustedNetworkTime = 0L
        baseElapsedRealtime = 0L
        sp?.edit()?.clear()?.apply()
    }
}
