package com.google.android.accessibility.ext.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import org.intellij.lang.annotations.Pattern
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.KeyGenerator
import kotlin.math.abs
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/*
* 核心功能:  负责时间防篡改逻辑
* 双时间源模式（无封号版）
* 公网时间与会员时间解耦
* ✔ 飞行模式绕不过
✔ 管家断网绕不过
✔ VPN 阻断绕不过
✔ DNS 劫持绕不过
✔ 系统回拨绕不过
✔ SP 篡改绕不过
✔ 重启绕不过
*
* 🚀 推荐调用流程（双源）
* App启动
* // 1. 先更新公网时间（百度/腾讯）
HYSJTimeSecurityManager.updateTrustedTime(networkTime)

// 2. 如果登录成功，从你服务器拿会员时间
HYSJTimeSecurityManager.updateHuiYuanTime(expireTimestamp = expireTimestamp)
*   判断是否会员
* if (HYSJTimeSecurityManager.isKYSJValid()) {
    // 有效
}
*
*
* | 场景         | 结果         |
| ---------- | ---------- |
| 断网 + App重启 | ✅ 离线时间继续计算 |
| 断网 + 设备重启  | ❌ 直接失效     |
| 联网重新同步时间   | ✅ 恢复正常     |

*
* 设备重启后只有一种恢复方式： 重新同步网络时间
*
* */

object HYSJTimeSecurityManager {

    private const val SP_NAME = "hysj_time_secure"
    private const val KEY_DATA = "secure_data"
    private const val KEY_SIGN = "secure_sign"
    private const val BOOT_TIME_TOLERANCE = 15000L

    // 允许本地时间和可信时间最大误差 30 分钟
    private const val MAX_TIME_DRIFT = 30 * 60 * 1000L
    // 默认允许离线小时数
    private const val DEFAULT_OFFLINE_HOURS = 1L
    //可信网络时间
    @Volatile private var trustedNetworkTime: Long = 0L
    //本地运行时间
    @Volatile private var baseElapsedRealtime: Long = 0L
    @Volatile private var lastSyncElapsedRealtime: Long = 0L
    // 会员过期时间（服务器下发）
    @Volatile private var cachedExpireTimestamp: Long = 0L
    // （保存开机时间）用于检测设备重启
    @Volatile private var savedBootTime: Long = 0L
    // 防SP回滚
    @Volatile private var secureNonce: Long = 0L
    // 首次运行elapsed时间
    @Volatile private var firstRunElapsedRealtime: Long = 0L

    // 防冷启动SP回滚
    @Volatile private var maxNonce: Long = 0L
    // ✅【自动恢复】防止重复恢复（防抖）
    @Volatile private var recovering = false

    //给“会员状态”一个短暂缓存宽限
    @Volatile private var recoverTimestamp: Long = 0L
    private const val KEY_RECOVER_TS = "recover_ts"
    @Volatile private var lastRecoverRealTime: Long = 0L
    private const val RECOVER_COOLDOWN = 10 * 60 * 1000L // 10分钟
    private val stateLock = Any()// 写操作锁

    private var sp: android.content.SharedPreferences? = null

    const val defaultTimeString = "1970-01-01 00:00:00"


    private val formatterMap = mutableMapOf<String, SimpleDateFormat>()

    /**
     * 1️⃣ Application 中初始化
     */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context = appContext) {
        sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        loadFromSp(context)
        recoverTimestamp = sp?.getLong(KEY_RECOVER_TS, 0L) ?: 0L
        if (firstRunElapsedRealtime == 0L) {
            firstRunElapsedRealtime = SystemClock.elapsedRealtime()
            saveToSp(context)
        }
    }

    /**
     * 2️⃣ ① 更新公网时间（百度/腾讯等）
     * 每次获取服务器时间后调用
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
        synchronized(stateLock) {
            trustedNetworkTime = networkTimestamp
            baseElapsedRealtime = SystemClock.elapsedRealtime()
            lastSyncElapsedRealtime = baseElapsedRealtime
            // 保存当前设备开机时间
            savedBootTime = getCurrentBootTime()
            saveToSp(context)
        }
    }
    //  获取当前设备开机时间
    //虽然真实时间可被篡改，但你后续已经有 isSystemTimeValid 来防御时间篡改了，所以这里是安全的
    fun getCurrentBootTime(): Long {
        //return SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
        // 逻辑：当前真实时间戳 - 开机到现在流逝的时间 = 固定的设备开机时间戳
        return System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }

    // 检测设备是否重启
    fun isDeviceRebooted(): Boolean {

        if (savedBootTime == 0L) return false
        //底层流逝时间回退检测。如果当前流逝时间比基准时间还小，绝对是发生了重启。
        if (baseElapsedRealtime > 0L && SystemClock.elapsedRealtime() < baseElapsedRealtime) {
            return true
        }

        val currentBoot = getCurrentBootTime()

        return abs(currentBoot - savedBootTime) > BOOT_TIME_TOLERANCE
    }
    // =============================
    // 3️⃣ 更新会员时间（自己服务器）
    // =============================

    @JvmStatic
    @JvmOverloads
    fun updateHuiYuanTime(
        context: Context = appContext,
        expireTimestamp: Long = 0L,
        expireTimeString: String = "",
        pattern: String = "yyyy-MM-dd HH:mm:ss"
    ) {

        val finalExpireTime = when {
            expireTimestamp > 0L -> expireTimestamp

            expireTimeString.isNotEmpty() ->
                parseTimeStringToMillis(expireTimeString, pattern)

            else -> 0L
        }
        synchronized(stateLock) {
            cachedExpireTimestamp = finalExpireTime
            saveToSp(context)
        }
    }

    /**
     * 3️⃣.2 判断会员是否有效
     * expireTimestamp 必须是服务器下发的会员毫秒时间戳
     */
    @JvmStatic
    @JvmOverloads
    fun isKYSJValid(
        context: Context = appContext,
        allowOfflineHours: Long = DEFAULT_OFFLINE_HOURS
    ): Boolean {
        return checkTimeSecurityStatus(context, allowOfflineHours).isValid
    }

    /**
     * 获取安全的HY过期时间
     *
     * 特点：
     * - 只有在时间体系“安全”的情况下才返回
     * - 否则返回 0（等同未同步）
     *
     */
    @JvmStatic
    @JvmOverloads
    fun getHYExpireTimestampSafe(context: Context = appContext): Long {

        val status = checkTimeSecurityStatus(context)

        return if (status.isValid || status.reason == TimeSecurityReason.VIP_EXPIRED) {
            cachedExpireTimestamp
        } else {
            0L
        }
    }

    /**
     * 是否已经同步过HY时间
     */
    @JvmStatic
    fun isHYTimeSynced(): Boolean {
        return cachedExpireTimestamp > 0L
    }
    //清空HY 时间
    @JvmStatic
    fun clearHYInfo(context: Context = appContext) {
        synchronized(stateLock) {
            cachedExpireTimestamp = 0L
            saveToSp(context)
        }
    }

    /**
     * 获取可信当前时间
     * 核心算法：网络基准时间 + 真实经过时间
     */
    @JvmStatic
    @JvmOverloads
    fun getTrustedNow(isSystem: Boolean = false): Long {

        if (trustedNetworkTime == 0L) {
            return  if (isSystem) {
                System.currentTimeMillis()
            }else{
                0L
            }
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val passed = maxOf(0L, nowElapsed - baseElapsedRealtime)

        return trustedNetworkTime + passed
    }


    private val installTimeCache: Long by lazy {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .firstInstallTime
    }
    //首次安装30分钟豁免
    fun isInstallGracePeriod(): Boolean {

        if (firstRunElapsedRealtime <= 0L) return false

        val nowElapsed = SystemClock.elapsedRealtime()
        // 如果当前流逝时间小于首次运行记录的流逝时间，说明设备绝对重启过
        if (nowElapsed < firstRunElapsedRealtime) return false
        // 30分钟豁免
        val inGrace = nowElapsed - firstRunElapsedRealtime <= MAX_TIME_DRIFT

        if (!inGrace) return false
        // 检测是否修改系统时间骗豁免
        val installTime = installTimeCache

        val systemNow = System.currentTimeMillis()
        if (systemNow < installTime) return false

        // 如果系统时间距离安装时间非常久，却还在30分钟豁免
        if (abs(systemNow - installTime) > 7 * 24 * 60 * 60 * 1000L) {
            return false
        }

        return true

    }

    /**
     * 时间合法性判断
     * 判断是否有人为修改系统时间
     * 如果系统时间和可信时间差超过 30 分钟则判定异常
     * 如果网络时间和本地时间对比相差 30 分钟之内，则判定没有人为修改
     *
     * 网络时间 + 运行时间 = 可信时间
     *
     * 如果
     * 系统时间 和 可信时间 差 > 30分钟
     *
     * = 用户修改了系统时间
     */
    @JvmStatic
    @JvmOverloads
    fun isSystemTimeValid(context: Context = appContext): Boolean {
         //如果返回false的话   在安装 > 30分钟 后  还没同步网络时间
        //会被误判为 系统时间被修改
        //如果没有可信网络时间
        //只允许安装30分钟内运行
        //否则必须联网同步时间
        if (trustedNetworkTime == 0L) {
            return isInstallGracePeriod()
        }

        val trustedNow = getTrustedNow()
        val systemNow = System.currentTimeMillis()

        val drift = abs(trustedNow - systemNow)

        return drift <= MAX_TIME_DRIFT
    }

    /** 离线时间控制
     * 是否超过允许的离线时间
     *
     * @param allowOfflineHours 允许离线小时数
     */
    @JvmStatic
    @JvmOverloads
    fun isOfflineExpired(
        allowOfflineHours: Long = DEFAULT_OFFLINE_HOURS
    ): Boolean {
        if (isDeviceRebooted()) return true
        if (trustedNetworkTime == 0L) {
            // 从未同步过时间，直接视为超时
            return true
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val offlineLimit = allowOfflineHours * 60 * 60 * 1000L
        val offlineTime = nowElapsed - lastSyncElapsedRealtime

        return offlineTime > offlineLimit
    }

    /**
     * 获取已离线毫秒数
     */
    @JvmStatic
    fun getOfflinePassedMillis(): Long {
        if (isDeviceRebooted()) return Long.MAX_VALUE

        if (trustedNetworkTime == 0L || lastSyncElapsedRealtime == 0L) {
            return Long.MAX_VALUE
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val offlineMillis = nowElapsed - lastSyncElapsedRealtime

        return if (offlineMillis < 0) 0L else offlineMillis
    }


    /**
     * 获取已离线的小时数（自上次成功同步网络时间起）
     *
     * 返回：
     * - 如果从未同步过网络时间，返回 Long.MAX_VALUE
     * - 否则返回已离线的小时数（向下取整）
     */
    @JvmStatic
    fun getOfflinePassedHours(): Long {
        // 设备重启或者未同步过网络时间 → 最大值
        if (isDeviceRebooted()) return Long.MAX_VALUE
        if (trustedNetworkTime == 0L || lastSyncElapsedRealtime == 0L) {
            // 从未同步过网络时间
            return Long.MAX_VALUE
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val offlineMillis = nowElapsed - lastSyncElapsedRealtime

        if (offlineMillis <= 0) return 0L

        return offlineMillis / (60 * 60 * 1000L)
    }
    /**
     * 获取剩余可离线分钟数
     *
     * @param limitHours 允许的最大离线小时数
     *
     * 返回：
     * - 如果从未同步过网络时间，返回 0
     * - 如果已经超出限制，返回 0
     * - 否则返回剩余可离线分钟数
     */
    @JvmStatic
    @JvmOverloads
    fun getOfflineRemainMinutes(limitHours: Long = DEFAULT_OFFLINE_HOURS): Long {
        // 设备重启直接返回0
        if (isDeviceRebooted()) return 0
        if (trustedNetworkTime == 0L || lastSyncElapsedRealtime == 0L) {
            return 0L
        }

        if (limitHours <= 0) return 0L

        val nowElapsed = SystemClock.elapsedRealtime()
        val passedMillis = nowElapsed - lastSyncElapsedRealtime

        val limitMillis = limitHours * 60 * 60 * 1000L
        val remainMillis = limitMillis - passedMillis

        if (remainMillis <= 0) return 0L

        return remainMillis / (60 * 1000L)
    }

    @JvmStatic
    @JvmOverloads
    fun getOfflineRemainTimeText(limitHours: Long = DEFAULT_OFFLINE_HOURS): String {
        // 设备重启直接返回0
        if (isDeviceRebooted()) return "0分钟"
        if (trustedNetworkTime == 0L) return "App未联网"
        val lastSync = lastSyncElapsedRealtime
        if (lastSync == 0L || limitHours <= 0) return "0分钟"

        val remainMillis = limitHours * 3600000L -
                (SystemClock.elapsedRealtime() - lastSync)

        if (remainMillis <= 0) return "0分钟"

        val totalMinutes = remainMillis / 60000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
            hours > 0 -> "${hours}小时"
            else -> "${minutes}分钟"
        }
    }

    // ✅【自动恢复核心】
// ✅【修改】增加锁
    private fun recoverIfNeeded(context: Context) {
        synchronized(stateLock) {

            val now = SystemClock.elapsedRealtime()
            //限制恢复频率
            if (now - lastRecoverRealTime < RECOVER_COOLDOWN) {
                sendLog("恢复过于频繁，拒绝")
                return
            }
            lastRecoverRealTime = now

            if (recovering) return
            recovering = true
            try {
                sendLog("触发SP自动恢复")

                trustedNetworkTime = 0L
                baseElapsedRealtime = 0L
                lastSyncElapsedRealtime = 0L
                cachedExpireTimestamp = 0L
                savedBootTime = 0L

                secureNonce = 0L
                maxNonce = 0L

                // ❗防止重新进入首装豁免
                firstRunElapsedRealtime = -1L
                // ✅记录恢复时间（持久化）
                recoverTimestamp = SystemClock.elapsedRealtime()
                sp?.edit()?.putLong(KEY_RECOVER_TS, recoverTimestamp)?.commit()
                // ✅ 写入干净状态
                saveToSp(context)


            } finally {
                recovering = false
            }
        }
    }

    // =============================
    // SP签名保护
    // =============================

    private fun saveToSp(context: Context) {
        synchronized(stateLock) {
            // nonce自增
            secureNonce = maxOf(secureNonce + 1, maxNonce + 1)
            maxNonce = secureNonce
            val rawData =
                "$trustedNetworkTime|" +
                        "$baseElapsedRealtime|" +
                        "$lastSyncElapsedRealtime|" +
                        "$cachedExpireTimestamp|"+
                        "$savedBootTime|" +
                        "$secureNonce|" +
                        "$firstRunElapsedRealtime"

            val sign = generateHmac(context, rawData)

            sp?.edit()
                ?.putString(KEY_DATA, rawData)
                ?.putString(KEY_SIGN, sign)
                ?.putLong("max_nonce", maxNonce)
                ?.commit()
        }

    }
    // =============================
    // SP校验
    // =============================

    private fun verifySp(context: Context): Boolean {

        val rawData = sp?.getString(KEY_DATA, null) ?: return true
        if (rawData.isEmpty()) return true

        val savedSign = sp?.getString(KEY_SIGN, null) ?: return false
        val realSign = generateHmac(context, rawData)

        if (realSign != savedSign) {
            return false
        }

        // 防 SP 回滚攻击
        val parts = rawData.split('|')
        if (parts.size < 7) return false

        val nonce = parts[5].toLongOrNull() ?: return false

        val savedMaxNonce = sp?.getLong("max_nonce", 0L) ?: 0L

        if (nonce < savedMaxNonce) {
            return false
        }

        secureNonce = nonce
        maxNonce = savedMaxNonce

        return true
    }

    private fun loadFromSp(context: Context) {
        val ok = verifySp(context)
        if (!ok) {
            recoverIfNeeded(context)
            return
        }

        maxNonce = sp?.getLong("max_nonce", 0L) ?: 0L
        val rawData = sp?.getString(KEY_DATA, null) ?: return
        val parts = rawData.split('|')
        if (parts.size < 7) return

        trustedNetworkTime = parts[0].toLongOrNull() ?: 0L
        baseElapsedRealtime = parts[1].toLongOrNull() ?: 0L
        lastSyncElapsedRealtime = parts[2].toLongOrNull() ?: 0L
        cachedExpireTimestamp = parts[3].toLongOrNull() ?: 0L
        savedBootTime = parts[4].toLongOrNull() ?: 0L
        secureNonce = parts[5].toLongOrNull() ?: 0L
        firstRunElapsedRealtime = parts[6].toLongOrNull() ?: 0L
    }

    private fun generateHmac(context: Context, data: String,ruan: Boolean = true): String {
        return if (ruan){
            val secret = getDeviceSecret(context)
            val mac = Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(keySpec)
            Base64.encodeToString(mac.doFinal(data.toByteArray()), Base64.NO_WRAP)
        }else{
            try {
                // 优先使用 Android 系统底层的 TEE 硬件 KeyStore 加密签名，防逆向、防提取
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val alias = "hysj_hardware_hmac_alias"

                val secretKey: SecretKey = if (keyStore.containsAlias(alias)) {
                    keyStore.getKey(alias, null) as SecretKey
                } else {
                    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
                    keyGenerator.init(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build()
                    )
                    keyGenerator.generateKey()
                }
                Log.e("sp怎么回事", "alias exist = ${keyStore.containsAlias(alias)}" )
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(secretKey)
                Base64.encodeToString(mac.doFinal(data.toByteArray()), Base64.NO_WRAP)
            } catch (e: Exception) {
                // 降级方案：如果设备不支持硬件加密，退回到原始的软加密方式
                val secret = getDeviceSecret(context)
                val mac = Mac.getInstance("HmacSHA256")
                val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
                mac.init(keySpec)
                Base64.encodeToString(mac.doFinal(data.toByteArray()), Base64.NO_WRAP)
            }
        }

    }

    private fun getDeviceSecret(context: Context): String {

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val model = Build.MODEL ?: "unknown"

        return "VIP_SECRET_${androidId}_${model}_2025"
    }

    // Hook/Xposed检测
    private val HOOK_KEYWORDS = arrayOf(
        "de.robv.android.xposed",
        "lsposed",
        "edxposed",
        "frida",
        "substrate",
        "zygisk",
        "riru",
        "magisk"
    )

    private val hookEnvironment by lazy { detectHookEnvironment() || isXposedPresent() }

    val isHookEnvironment: Boolean
        get() = hookEnvironment

    private fun detectHookEnvironment(): Boolean {

        return try {

            val stack = Thread.currentThread().stackTrace

            for (element in stack) {
                val cls = element.className
                for (key in HOOK_KEYWORDS) {
                    if (cls.contains(key)) return true
                }
            }

            false

        } catch (_: Throwable) {
            false
        }
    }

    private fun isXposedPresent(): Boolean {
        return try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (_: Throwable) {
            false
        }
    }

    // Debug检测
    fun isDebuggerAttached(): Boolean {
        return android.os.Debug.isDebuggerConnected()
    }

    // ===============================
    // 系统网络检查
    // ===============================
    @JvmStatic
    @JvmOverloads
    fun isNetworkAvailable(context: Context = appContext,valid: Boolean = false): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        val b = if (valid){
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }else{
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        return b
    }


    /**
     * 清除可信时间（可选）
     */
    @JvmStatic
    fun clear() {
        synchronized(stateLock) {
            trustedNetworkTime = 0L
            baseElapsedRealtime = 0L
            lastSyncElapsedRealtime = 0L
            cachedExpireTimestamp = 0L
            savedBootTime = 0L
            secureNonce = 0L
            //防止重新触发首装30分钟豁免
            firstRunElapsedRealtime = -1L
            sp?.edit()?.clear()?.commit()
        }

    }



    @JvmStatic
    @JvmOverloads
    fun checkTimeSecurityStatus(
        context: Context = appContext,
        allowOfflineHours: Long = DEFAULT_OFFLINE_HOURS
    ): TimeSecurityStatus {
        val networkAvailable = isNetworkAvailable(context)
        val offlinePassed = getOfflinePassedHours()  //保持 Long.MAX_VALUE 在设备重启或未同步时
        val offlineRemain = getOfflineRemainTimeText(allowOfflineHours)
        //  1️⃣ Hook检测
        if (isHookEnvironment) {
            sendLog("App被hook")
            return TimeSecurityStatus(
                isValid = false,
                reason =  TimeSecurityReason.HOOK_DETECTED,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }

        //  2️⃣ Debug检测
        if (isDebuggerAttached()) {
            sendLog("App被debug")
            return TimeSecurityStatus(
                isValid = false,
                reason =  TimeSecurityReason.DEBUGGER_DETECTED,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }


        // 13️⃣ SP篡改检测
        if (!verifySp(context)) {
            // ✅【自动恢复触发】
            recoverIfNeeded(context)
            // ✅恢复后再校验一次
            if (!verifySp(context)) {
                sendLog("SP异常 → 恢复失败")
                return TimeSecurityStatus(
                    isValid = false,
                    reason = TimeSecurityReason.SP_TAMPERED,
                    isNetworkAvailable = networkAvailable,
                    offlinePassedHours = offlinePassed,
                    offlineRemainMinutes = offlineRemain
                )
            }
            sendLog("SP异常 → 已自动恢复成功")
        }
        // 安装30分钟豁免
        if (isInstallGracePeriod()) {
            sendLog("首次安装的30分钟内")
            return TimeSecurityStatus(
                isValid = true,
                reason =  TimeSecurityReason.OK,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }



        // 4️⃣ 设备重启
        if (isDeviceRebooted()) {
            sendLog("设备重启了,待联网后恢复")
            return TimeSecurityStatus(
                isValid = false,
                reason =  TimeSecurityReason.DEVICE_REBOOTED,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }

        // 未同步网络时间
        if (trustedNetworkTime == 0L) {
            sendLog("App还未联网")
            return TimeSecurityStatus(
                isValid = false,
                reason =  TimeSecurityReason.NETWORK_NOT_SYNCED,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }
        
        // 离线时间过长
        if (isOfflineExpired(allowOfflineHours)){
            sendLog("App离线时间过长")
            return TimeSecurityStatus(
                isValid = false,
                reason =  TimeSecurityReason.OFFLINE_EXPIRED,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }
        //  系统时间被修改（防回拨）
        if (!isSystemTimeValid()) {
            sendLog("设备时间被修改了")
            return TimeSecurityStatus(
                isValid = false,
                reason =  TimeSecurityReason.SYSTEM_TIME_INVALID,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }
        // 未同步会员时间 会员时间还没获取到
        //包含两种场景：
        //1 首次安装还没登录
         //2 SP被篡改导致数据清空

        //恢复窗口逻辑（必须联网）
        val now = SystemClock.elapsedRealtime()
        if (cachedExpireTimestamp <= 0L) {
            if (recoverTimestamp > 0) {

                val delta = now - recoverTimestamp

                // ✅ 仅限5分钟 + 必须联网
                if (delta < 5 * 60 * 1000L && networkAvailable) {
                    sendLog("恢复窗口内，等待服务器同步")
                    return TimeSecurityStatus(true, TimeSecurityReason.OK, networkAvailable, 0, "0")
                }

                // ✅超时清理
                if (delta >= 5 * 60 * 1000L) {
                    recoverTimestamp = 0L
                    sp?.edit()?.putLong(KEY_RECOVER_TS, 0L)?.commit()
                }
            }

            sendLog("App当前是免费版(未更新)")
            return TimeSecurityStatus(
                isValid = false,
                reason = TimeSecurityReason.VIP_TIME_NOT_SYNCED,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }

        // VIP过期   会员真的过期
        if (cachedExpireTimestamp <= getTrustedNow()) {
            sendLog("App当前是免费版(已过期)")
            return TimeSecurityStatus(
                isValid = false,
                reason =  TimeSecurityReason.VIP_EXPIRED,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain
            )
        }


        // 正常
        return TimeSecurityStatus(
            isValid = true,
            reason =  TimeSecurityReason.OK,
            isNetworkAvailable = networkAvailable,
            offlinePassedHours = offlinePassed,
            offlineRemainMinutes = offlineRemain
        )

    }
    //配合前面的 formatterMap，添加同步锁，保证线程安全
    @JvmStatic
    @Synchronized
    private fun getFormatter(patt: String): SimpleDateFormat {
        return formatterMap.getOrPut(patt) {
            SimpleDateFormat(patt, Locale.ROOT).apply { isLenient = true }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun parseTimeStringToMillis(timeStr: String, patt: String = "yyyy-MM-dd HH:mm:ss"): Long =
        runCatching { getFormatter(patt).parse(timeStr)?.time ?: 0L }.getOrDefault(0L)

    @JvmStatic
    @JvmOverloads
    fun parseMillisToTimeString(timeMillis: Long, patt: String = "yyyy-MM-dd HH:mm:ss"): String =
        runCatching { getFormatter(patt).format(Date(timeMillis)) }.getOrDefault("")

}

// 失败原因枚举
enum class TimeSecurityReason {
   // 正常
    OK,
   //会员时间未同步
    VIP_TIME_NOT_SYNCED,
    // 会员过期
    VIP_EXPIRED,
    //超过离线限制
    OFFLINE_EXPIRED,

    //系统时间异常（回拨 / 快进）
    SYSTEM_TIME_INVALID,
    //设备重启
    DEVICE_REBOOTED,
    //网络时间未同步
    NETWORK_NOT_SYNCED,
    //sp被修改
    SP_TAMPERED,
    //hook检测
    HOOK_DETECTED,
    //debug检测
    DEBUGGER_DETECTED
}

/**
 * 时间安全统一状态模型
 */
data class TimeSecurityStatus(

    // 是否通过所有安全校验
    val isValid: Boolean,

    //失败原因
    val reason: TimeSecurityReason,

    //网络是否可用 不参与安全校验
    val isNetworkAvailable: Boolean,

    // 已离线小时数
    val offlinePassedHours: Long,

    // 剩余可离线小时分钟数
    val offlineRemainMinutes: String
)

