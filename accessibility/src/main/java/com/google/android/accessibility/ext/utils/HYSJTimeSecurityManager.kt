package com.google.android.accessibility.ext.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
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
    private const val BOOT_TIME_TOLERANCE = 2 * 60 * 1000L // 建议2分钟
    //熔断最大时间
    val MAX_FALLBACK_TIME = 30 * 24 * 60 * 60 * 1000L // 1个月

    // 允许本地时间和可信时间最大误差 30 分钟
    private const val MAX_TIME_DRIFT = 30 * 60 * 1000L
    // 默认允许离线小时数
    private const val DEFAULT_OFFLINE_HOURS = 1L
    //可信网络时间
    @Volatile private var trustedNetworkTime: Long = 0L
    //本地运行时间
    @Volatile private var baseElapsedRealtime: Long = 0L
    @Volatile private var lastSyncElapsedRealtime: Long = 0L

    // 正式会员（服务器）
    @Volatile private var serverExpireTimestamp: Long = 0L
    // 广告会员（本地）
    @Volatile private var adExpireTimestamp: Long = 0L

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
    // ✅最后一次有效会员（用于熔断兜底）
    @Volatile private var lastValidExpireTimestamp: Long = 0L

    // BOOT_COUNT 重启检测
    @Volatile private var savedBootCount: Int = 0
    @Volatile private var lastSyncBootCount: Int = 0
    //记录“检测到重启的时间”
    @Volatile private var rebootDetectedRealtime: Long = 0L

    private const val KEY_RECOVER_COUNT = "recover_count"
    private const val KEY_RECOVER_DAY = "recover_day"
    // 记录“最后一次同步时的可信时间（网络时间）”
    @Volatile private var lastSyncTrustedTime: Long = 0L
    //容忍5秒   部分设备单调递增不稳
    val rollbackTolerance = 5_000L

    // 同步判定窗口（5分钟内算“刚同步”）
    private const val JUST_SYNC_WINDOW = 5 * 60 * 1000L
    @Volatile private var justSyncedFlag = false

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
        loadFromSp(context)//savedBootCount 会读取保存的值

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
            //记录可信时间基准（统一时间体系核心）
            lastSyncTrustedTime = networkTimestamp
            justSyncedFlag = true
            //记录BOOT_COUNT
            val boot  = getBootCount()
            lastSyncBootCount = boot
            savedBootCount = boot
            saveToSp(context)
        }
    }

    /**
     * 获取可信当前时间
     * 核心算法：网络基准时间 + 真实经过时间
     */
    @JvmStatic
    @JvmOverloads
    fun getTrustedNow(): Long {
        // ❗ 没有可信时间
        if (trustedNetworkTime == 0L) {
            return  0L
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val passed = nowElapsed - baseElapsedRealtime
        // ❗ 防御：elapsed异常（极小概率）
        if (passed + rollbackTolerance < 0) {
            sendLog("elapsedRealtime异常,大概率重启了")
            return 0L
        }
        return trustedNetworkTime + passed
    }

    // =============================
    // ✅ 是否“刚刚同步过时间”
    // =============================
    @JvmStatic
    fun isJustSynced(withinMillis: Long = JUST_SYNC_WINDOW): Boolean {

        val last = lastSyncElapsedRealtime
        if (last <= 0L) return false

        val now = SystemClock.elapsedRealtime()
        val delta = now - last

        // 防御 elapsed 异常（重启 / 回退）
        if (delta + rollbackTolerance < 0) return false

        return delta in 0..withinMillis
    }

    // 检测“重启后是否已恢复可信时间”（恢复判断）
    fun isResyncedAfterReboot(context: Context = appContext): Boolean {
        // 🔥① 主判定：BOOT_COUNT
        val currentBoot = getBootCount()

        return trustedNetworkTime > 0 &&
                savedBootCount > 0 &&
                currentBoot > 0 &&
                currentBoot == savedBootCount
                // “最后一次同步时间，必须发生在检测到重启之后
        //其实说白了,如果是先同步时间,那么后续也检测不出重启了,自然是认为重启后恢复了
                && lastSyncElapsedRealtime + rollbackTolerance >= rebootDetectedRealtime
    }

    // 检测设备是否发生过重启 （状态判断）
    fun isDeviceRebooted(): Boolean {
        // 🔥① 主判定：BOOT_COUNT
        val currentBoot = getBootCount()
        if (savedBootCount > 0 && currentBoot >0 && currentBoot != savedBootCount) {
            sendLog("BOOT_COUNT变化 ($currentBoot) → 判定设备重启")
            return true
        }

        if (savedBootCount <= 0 || currentBoot <= 0) {
            // 🔥② 兜底：elapsedRealtime 回退
            val nowElapsed = SystemClock.elapsedRealtime()
            //baseElapsedRealtime在更新网络基准时间时也会随着更新
            //就是说 成功联网后  nowElapsed 应该一直大于 baseElapsedRealtime
            //故一旦发现是小于,可认定重启且未联网
            //但是呢 只要设备开机运行时间超过“上次记录值”，就算未联网,也是大于  这是个临界点也是个漏洞
            if (baseElapsedRealtime > 0L ){
                if (nowElapsed  + rollbackTolerance < baseElapsedRealtime) {
                    sendLog("elapsedRealtime 回退 → 判定设备重启")
                    return true
                }

                if (nowElapsed > baseElapsedRealtime && !justSyncedFlag) {
                    //设备开机运行时间超过“上次记录值”，但一直未联网
                    //sendLog("设备开机运行时间超过“上次记录值”，但一直未联网,判定设备重启")
                    //return true
                }
            }

        }



        return false
    }
    // 获取系统开机次数
    @JvmStatic
    @JvmOverloads
    fun getBootCount(context: Context = appContext): Int {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT
            )
        } catch (e: Exception) {
            0
        }
    }

    // =============================
    // ✅统一会员更新入口（核心）
    // =============================
    @JvmStatic
    @JvmOverloads
    fun updateMyHYExpire(
        context: Context = appContext,
        newExpire: Long,
        source: VipSource
    ) {
        synchronized(stateLock) {

            val now = getTrustedNow()

            // ❗必须有可信时间
            if (now <= 0L) {
                AliveUtils.toast(msg = "请先联网,再更新会员时间")
                sendLog("未同步可信时间，拒绝更新会员 [$source]")
                return
            }
            val MAX_VALID_EXPIRE = 20L * 365 * 24 * 60 * 60 * 1000 // 20年
            val safeExpire = minOf(newExpire, now + MAX_VALID_EXPIRE)
            when (source) {

                VipSource.SERVER -> {
                    serverExpireTimestamp = maxOf(serverExpireTimestamp, safeExpire)
                }

                VipSource.LOCAL_AD -> {
                    adExpireTimestamp = maxOf(adExpireTimestamp, safeExpire)
                }
            }


            //val currentExpire = getCurrentExpire()
            //sendLog("会员更新 [$source] -> ${parseMillisToTimeString(currentExpire)}")

            saveToSp(context)
        }
    }

    // =============================
    // 3️⃣ 服务器会员时间 发放（自己服务器）
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

        if (finalExpireTime <= 0L) return
        // ✅统一入口
        updateMyHYExpire(context, finalExpireTime, VipSource.SERVER)

    }

    // =============================
    // ✅广告会员时间 发放
    // =============================
    @JvmStatic
    @JvmOverloads
    fun updateADHuiYuanTime(
        context: Context = appContext,
        durationMillis: Long = 24 * 60 * 60 * 1000L // 默认1天
    ) {
        synchronized(stateLock) {

            val now = getTrustedNow() //有可能是0

            // ❗必须联网（防离线刷）
            if (!isNetworkAvailable(context)) {
                sendLog("广告会员发放失败：当前无有效网络")
                return
            }


            // ❗每日限制（防刷）
            if (!canGrantAdHYToday(context)) {
                sendLog("广告会员发放失败：今日已达上限")
                return
            }

            val base = maxOf(now, adExpireTimestamp)
            val newExpire = base + durationMillis

            // ✅统一入口
            updateMyHYExpire(context, newExpire, VipSource.LOCAL_AD)

            recordAdHYGrant(context)
        }
    }

    // =============================
    // ✅广告会员每日限制
    // =============================
    private fun getTodayKey(): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
        return sdf.format(Date())
    }
   //当天是否还能领ad会员
    private fun canGrantAdHYToday(context: Context): Boolean {
        val today = getTodayKey()
        val count = sp?.getInt("ad_vip_$today", 0) ?: 0
        return count < 100 // 每天最多100次
    }
   //记录一天的ad会员领取次数
    private fun recordAdHYGrant(context: Context) {

       synchronized(stateLock) {
           val today = getTodayKey()
           val count = sp?.getInt("ad_vip_$today", 0) ?: 0

           sp?.edit()
               ?.putInt("ad_vip_$today", count + 1)
               ?.commit()
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
            getCurrentExpire()
        } else {
            0L
        }
    }

    /**
     * 是否已经同步过HY时间
     */
    @JvmStatic
    fun isHYTimeSynced(): Boolean {
        return getCurrentExpire() > 0L
    }
    //清空HY 时间
    @JvmStatic
    fun clearHYInfo(context: Context = appContext) {
        synchronized(stateLock) {
            serverExpireTimestamp = 0L
            adExpireTimestamp = 0L
            lastValidExpireTimestamp = 0L
            saveToSp(context)
        }
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
        // 设备重启（elapsed 回退）→ 直接失效
        if (nowElapsed < firstRunElapsedRealtime) {
            //sendLog("试用失效：检测到设备重启")
            return false
        }
        val passed = nowElapsed - firstRunElapsedRealtime
        // 严格30分钟窗口（只基于 elapsedRealtime）
        return passed <= MAX_TIME_DRIFT

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
    @Volatile private var lastTrustedNow: Long = 0L

    @JvmStatic
    @JvmOverloads
    fun isSystemTimeValid(context: Context = appContext): Boolean {
         //如果返回false的话   在安装 > 30分钟 后  还没同步网络时间
        //会被误判为 系统时间被修改
        //如果没有可信网络时间
        //只允许安装30分钟内运行
        //否则必须联网同步时间
        if (trustedNetworkTime == 0L) {
            Log.e("isSystemTimeValid", "trustedNetworkTime==0" )
            return false // 如果返回true 不在这里判,交给NETWORK_NOT_SYNCED
        }

        val trustedNow = getTrustedNow()
        val systemNow = System.currentTimeMillis()

        // 可信时间不能倒退

        if (lastTrustedNow > 0 && trustedNow + rollbackTolerance < lastTrustedNow) {
            Log.e("isSystemTimeValid", "可信时间倒退"+(trustedNow + rollbackTolerance - lastTrustedNow) )
            sendLog("可信时间倒退")
            return false
        }

        lastTrustedNow = trustedNow
        val drift = abs(trustedNow - systemNow)

        return drift <= MAX_TIME_DRIFT
    }

    /** 离线时间控制
     * 是否超过允许的离线时间
     * Expired 意思 过期的
     * @param allowOfflineHours 允许离线小时数
     */
    @JvmStatic
    @JvmOverloads
    fun isOfflineExpired(
        allowOfflineHours: Long = DEFAULT_OFFLINE_HOURS
    ): Boolean {
        //重启算过期
        if (isDeviceRebooted()) return true //
        val lastSync = lastSyncElapsedRealtime
        if (lastSync <= 0L || allowOfflineHours <= 0) return false
        //有种情况下,重启  nowElapsed 会很小  lastSync 会很大  offlineMillis为负数
        val nowElapsed = SystemClock.elapsedRealtime()
        val offlineMillis = nowElapsed - lastSync
        if (offlineMillis + rollbackTolerance < 0) {
            sendLog("offlineMillis异常（负数）")
            return true
        }
        val offlineLimit = allowOfflineHours * 60 * 60 * 1000L

        return offlineMillis > offlineLimit
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

        return if (offlineMillis + rollbackTolerance < 0) Long.MAX_VALUE else maxOf(0L, offlineMillis)
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

        if (offlineMillis + rollbackTolerance <= 0) return Long.MAX_VALUE

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

        if (remainMillis + rollbackTolerance <= 0) return 0L

        return remainMillis / (60 * 1000L)
    }

    @JvmStatic
    @JvmOverloads
    fun getOfflineRemainTimeText(limitHours: Long = DEFAULT_OFFLINE_HOURS): String {
        // 设备重启直接返回0
        if (isDeviceRebooted()) return "0分钟"
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

    private fun canUseRecover(context: Context): Boolean {
        val today = getTodayKey()

        val lastDay = sp?.getString(KEY_RECOVER_DAY, "") ?: ""
        val count = sp?.getInt(KEY_RECOVER_COUNT, 0) ?: 0

        if (today != lastDay) {
            sp?.edit()
                ?.putString(KEY_RECOVER_DAY, today)
                ?.putInt(KEY_RECOVER_COUNT, 0)
                ?.commit()
            return true
        }

        return count < 10 // ✅ 每天最多10次
    }

    // ✅【自动恢复核心】
    /* 触发条件只有一个：👉 SP 校验失败（被篡改 / 回滚 / 签名不一致）
    恢复后的系统状态：相当于  一个“刚安装但没有30分钟豁免”的App
    *
    *
    * */
    private fun recoverIfNeeded(context: Context, preserveFirstRun: Boolean = false) {
        synchronized(stateLock) {
            //每日恢复次数限制
            if (!canUseRecover(context)) {
                sendLog("恢复次数超限")
                return
            }
            val now = SystemClock.elapsedRealtime()
            //限制恢复频率 （时间防抖）
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
                lastValidExpireTimestamp = 0L
                lastSyncBootCount = 0
                lastSyncTrustedTime = 0L
                serverExpireTimestamp = 0L
                adExpireTimestamp = 0L
                secureNonce = 0L
                maxNonce = 0L

                if (!preserveFirstRun) {
                    // 防止重新进入首装豁免
                    firstRunElapsedRealtime = -1L
                }
                //记录恢复时间（持久化）
                recoverTimestamp = SystemClock.elapsedRealtime()
                sp?.edit()?.putLong(KEY_RECOVER_TS, recoverTimestamp)?.commit()
                //记录恢复次数
                val today = getTodayKey()
                val count = sp?.getInt(KEY_RECOVER_COUNT, 0) ?: 0
                sp?.edit()
                    ?.putInt(KEY_RECOVER_COUNT, count + 1)
                    ?.putString(KEY_RECOVER_DAY, today)
                    ?.commit()
                //写入干净状态
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
                        "$savedBootCount|" +
                        "$secureNonce|" +
                        "$firstRunElapsedRealtime|"+
                        "$lastValidExpireTimestamp|" +
                        "$serverExpireTimestamp|" +
                        "$adExpireTimestamp|" +
                        "$lastSyncBootCount|" +
                        "$lastSyncTrustedTime"

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
        synchronized(stateLock) {
            val hasOldData = sp?.contains(KEY_DATA) == true
            val rawData = sp?.getString(KEY_DATA, null)
            if (rawData.isNullOrEmpty()) {

                return if (!hasOldData) {
                    // ✅ 真正首次启动
                    true
                } else {
                    // ❌ 被删除 → 攻击
                    false
                }
            }



            val savedSign = sp?.getString(KEY_SIGN, null) ?: return false
            val realSign = generateHmac(context, rawData)

            if (realSign != savedSign) {
                return false
            }

            // 防 SP 回滚攻击
            val parts = rawData.split('|')
            if (parts.size < 11) return false

            val nonce = parts[4].toLongOrNull() ?: return false

            val savedMaxNonce = sp?.getLong("max_nonce", 0L) ?: 0L

            if (nonce < savedMaxNonce) {
                return false
            }

            secureNonce = nonce
            maxNonce = savedMaxNonce

            return true
        }

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
        if (parts.size < 11) return

        trustedNetworkTime = parts[0].toLongOrNull() ?: 0L
        baseElapsedRealtime = parts[1].toLongOrNull() ?: 0L
        lastSyncElapsedRealtime = parts[2].toLongOrNull() ?: 0L
        savedBootCount = parts[3].toIntOrNull() ?: 0
        secureNonce = parts[4].toLongOrNull() ?: 0L
        firstRunElapsedRealtime = parts[5].toLongOrNull() ?: 0L
        lastValidExpireTimestamp = parts[6].toLongOrNull() ?: 0L
        serverExpireTimestamp = parts[7].toLongOrNull() ?: 0L
        adExpireTimestamp = parts[8].toLongOrNull() ?: 0L
        lastSyncBootCount  = parts[9].toIntOrNull() ?: 0
        lastSyncTrustedTime = parts[10].toLongOrNull() ?: 0L

    }

    fun getCurrentExpire(): Long {
        return maxOf(serverExpireTimestamp, adExpireTimestamp)
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
                //Log.e("sp怎么回事", "alias exist = ${keyStore.containsAlias(alias)}" )
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
        val sp = context.getSharedPreferences("hysj_time_secure", Context.MODE_PRIVATE)

        var id = sp.getString("device_secret_id", null)

        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            sp.edit().putString("device_secret_id", id).commit()
        }

        return "VIP_SECRET_$id"
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
        allowOfflineHours: Long = DEFAULT_OFFLINE_HOURS ,
        maxRetryCount: Int = 10
    ): TimeSecurityStatus {

        val networkAvailable = isNetworkAvailable(context)
        val offlinePassed = getOfflinePassedHours()  //保持 Long.MAX_VALUE 在设备重启或未同步时
        val offlineRemain = getOfflineRemainTimeText(allowOfflineHours)
        //val justSynced = isJustSynced()
        fun timeSS(isValid: Boolean = true,reason: TimeSecurityReason = TimeSecurityReason.OK): TimeSecurityStatus {
            return TimeSecurityStatus(
                isValid = isValid,
                reason =  reason,
                isNetworkAvailable = networkAvailable,
                offlinePassedHours = offlinePassed,
                offlineRemainMinutes = offlineRemain,
                justSynced = justSyncedFlag
            )
        }
        //  1️⃣ Hook检测
        if (isHookEnvironment) {
            sendLog("App被hook")
            return timeSS(false,TimeSecurityReason.HOOK_DETECTED)
        }

        //  2️⃣ Debug检测
        if (isDebuggerAttached()) {
            sendLog("App被debug")
            return timeSS(false,TimeSecurityReason.DEBUGGER_DETECTED)
        }


        // 13️⃣ SP篡改检测
        if (!verifySp(context)) {
            // ✅【自动恢复触发】
            recoverIfNeeded(context)
            // ✅恢复后再校验一次
            if (!verifySp(context)) {
                sendLog("SP异常 → 恢复失败")
                return timeSS(false,TimeSecurityReason.SP_TAMPERED)
            }
            sendLog("SP异常 → 已自动恢复成功")
        }
        // 安装30分钟豁免
        if (isInstallGracePeriod()) {
            sendLog("首次安装的30分钟内")
            return timeSS()
        }



        // 获取网络可信时间
        val trustedNow = getTrustedNow()//获取当前可信时间
        if (trustedNow <= 0L) {
            //从安装到现在从未成功联网
            sendLog("App从未联过网,当前可信时间为（0）,请授予联网权限")
            return timeSS(false,TimeSecurityReason.NETWORK_NOT_SYNCED)
        }
        //既然走到这里,就说明从安装到现在是成功联网过的
        //但现在又没联网 2种情况:App重启或设备重启
        // ✅ 使用 repeat 循环等待 justSyncedFlag 变为 true
        if (!justSyncedFlag) {
            if (isDeviceRebooted()){
               //设备重启
                sendLog("开始等待网络时间同步...")
                var synced = false
                repeat(maxRetryCount) { index ->
                    if (justSyncedFlag) {
                        synced = true
                        sendLog("网络时间同步成功 (第${index + 1}次检查)")
                        Log.e("网络时间同步", "成功 (第${index + 1}次检查)")
                        return@repeat
                    }
                    // 每次间隔 300ms
                    SystemClock.sleep(300L)
                }

                if (!synced) {
                    sendLog("网络时间同步超时 (重试$maxRetryCount 次)")
                    Log.e("网络时间同步", "超时 (重试$maxRetryCount 次)")
                    return timeSS(false, TimeSecurityReason.NETWORK_NOT_SYNCED)
                }
            }else{
                //app重启
            }

        }


        // 4️⃣ 设备重启
        /*if (isDeviceRebooted()) {
            //只有进程重启（App 或设备），才会执行一次
            if (rebootDetectedRealtime == 0L) {
                rebootDetectedRealtime = SystemClock.elapsedRealtime()
            }
            // ⭐ 超过5秒 → 再判断是否恢复
            //很明显 第一次执行时间隔太短,进不来,第二次才能进来
            val delta = SystemClock.elapsedRealtime() - rebootDetectedRealtime
            if (delta > 5_000L) {
                if (!isResyncedAfterReboot()) {
                    sendLog("设备重启了,待联网后恢复")
                    return timeSS(false,TimeSecurityReason.DEVICE_REBOOTED)
                }
            }

        }*/


        //  系统时间被修改（防回拨）
        if (!isSystemTimeValid()) { //基于网络可信时间,故该判断放在后面
            sendLog("设备时间被修改了")
            return timeSS(false,TimeSecurityReason.SYSTEM_TIME_INVALID)
        }

        // 离线时间过长
        if (isOfflineExpired(allowOfflineHours)){
            sendLog("App离线时间过长")
            return timeSS(false,TimeSecurityReason.OFFLINE_EXPIRED)
        }

        // 未同步会员时间 会员时间还没获取到
        //包含两种场景：
        //1 首次安装还没登录
         //2 SP被篡改导致数据清空
        val expire = getCurrentExpire() //获取当前会员时间
        //会员时间<0 未开通过会员
        if (expire <= 0L) {
            sendLog("App当前是免费版(未开通过)")
            return timeSS(false,TimeSecurityReason.VIP_TIME_NOT_SYNCED)
        }

        // =============================
        // 会员时间<当前可信时间  VIP过期   会员真的过期
        if (expire <= trustedNow) {
            sendLog("App当前是免费版(已过期)")
            return timeSS(false,TimeSecurityReason.VIP_EXPIRED)
        }


        // 正常
        return timeSS()

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

// =============================
// 会员来源（统一体系关键）
// =============================
enum class VipSource {
    SERVER,
    LOCAL_AD
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
    val offlineRemainMinutes: String,
    // 是否刚同步过时间
    val justSynced: Boolean
)



