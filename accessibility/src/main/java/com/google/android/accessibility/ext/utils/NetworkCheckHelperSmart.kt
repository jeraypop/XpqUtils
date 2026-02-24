package com.google.android.accessibility.ext.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * 核心功能    : 负责联网 & 获取网络时间
 */
object NetworkHelperFullSmart {
    // ===============================
    // 状态定义
    // ===============================
    enum class NetStatus {
        NETWORK_UNAVAILABLE,       // 系统无网络 / 被禁用
        INTERNET_OK,               // 外网可访问
        MAYBE_BLOCKED_BY_FIREWALL, // 有网络但外网全部时间获取失败
        SERVER_OR_DNS_ERROR        // 网络正常但部分站点异常
    }

    data class NetworkCheckResult(
        val status: NetStatus,
        val time: String? = null,
        val timestamp: String? = null
    )

    /**
     * 全局测试站点（唯一集合：网络可用性检查 + 获取网络时间）
     */
    private val testUrls = listOf(
        "https://www.baidu.com",
        "https://www.taobao.com",
        "https://www.jd.com",
        "https://www.qq.com"
    )

    private const val CACHE_DURATION = 5 * 60 * 1000L // 5分钟缓存

    @Volatile
    private var lastResult: NetworkCheckResult? = null

    @Volatile
    private var lastCheckTime: Long = 0L

    private val cacheMutex = Mutex()

    private var runningJob: Deferred<NetworkCheckResult>? = null
    private var fangdouTime = 0L

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main
    )

    // =====  不获取时间 就纯粹外网检测缓存（5秒）=====
    private const val FAST_CHECK_CACHE_DURATION = 5000L

    @Volatile
    private var lastFastCheckResult: Boolean? = null

    @Volatile
    private var lastFastCheckTime: Long = 0L

    private val fastCheckMutex = Mutex()



    @JvmStatic
    @JvmOverloads
    fun updateMyTime(interval: Long = CACHE_DURATION) {
        if (intervalIsDuan(interval))return
        updateTimeForNet()
    }
    @JvmStatic
    @JvmOverloads
    fun intervalIsDuan(interval: Long = CACHE_DURATION): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - fangdouTime < interval) {
            return true
        }
        fangdouTime = currentTime
        return false
    }

    @JvmStatic
    fun updateTimeForNet() {
        appScope.launch {
            val result = NetworkHelperFullSmart.checkNetworkAndGetTimeSmart()
            if (result.status ==
                NetworkHelperFullSmart.NetStatus.INTERNET_OK
                && result.timestamp != null
            ) {
                //只要成功获取到网络时间：就更新可信时间基准
                HYSJTimeSecurityManager.updateTrustedTime(
                    networkTimestamp = result.timestamp.toLong()
                )
            }
        }
    }

    // ===============================
    // 对外入口（智能缓存版）
    // ===============================
    @JvmStatic
    @JvmOverloads
    suspend fun checkNetworkAndGetTimeSmart(
        context: Context = appContext
    ): NetworkCheckResult = coroutineScope {
        val now = System.currentTimeMillis()

        // ① 快速缓存命中
        lastResult?.let {
            if (now - lastCheckTime < CACHE_DURATION) {
                return@coroutineScope it
            }
        }

        cacheMutex.withLock {

            // 双重校验
            lastResult?.let {
                if (System.currentTimeMillis() - lastCheckTime < CACHE_DURATION) {
                    return@coroutineScope it
                }
            }

            // 已有任务正在执行
            runningJob?.let {
                return@coroutineScope it.await()
            }

            val job = async(Dispatchers.IO) {
                realCheckNetwork(context)
            }

            runningJob = job

            val result = try {
                job.await()
            } finally {
                runningJob = null
            }

            // 仅成功才缓存
            if (result.status == NetStatus.INTERNET_OK) {
                lastResult = result
                lastCheckTime = System.currentTimeMillis()
            }

            return@coroutineScope result
        }
    }

    // ===============================
    // 实际网络检测逻辑
    // ===============================

    suspend fun realCheckNetwork(
        context: Context = appContext
    ): NetworkCheckResult {

        if (!hasRequiredPermissions(context)) {
            return NetworkCheckResult(NetStatus.NETWORK_UNAVAILABLE)
        }

        //等待系统网络准备好（刚解锁场景）
        //val networkReady = waitForNetworkReady(context)
        //if (!networkReady) return NetworkCheckResult(NetStatus.NETWORK_UNAVAILABLE)


        if (!isNetworkAvailable(context)) {
            return NetworkCheckResult(NetStatus.NETWORK_UNAVAILABLE)
        }

        val timeResult = getNetworkTimeMultiSiteParallel()

        return if (timeResult != null) {
            NetworkCheckResult(
                NetStatus.INTERNET_OK,
                timeResult.first,
                timeResult.second
            )
        } else {
            val status = checkFirewallStatus()
            NetworkCheckResult(status)
        }
    }

    // ===============================
    // 系统网络检查
    // ===============================
    @JvmStatic
    @JvmOverloads
    fun isNetworkAvailable(context: Context = appContext,valid: Boolean = true): Boolean {
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


    // ===============================
    // 系统网络检测 + 等待准备
    // ===============================
     suspend fun waitForNetworkReady(
        context: Context = appContext,
        maxWaitMs: Long = 15000L,
        checkIntervalMs: Long = 1000L
    ): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val network = cm.activeNetwork
            val capabilities = network?.let { cm.getNetworkCapabilities(it) }

            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                return true
            }
            delay(checkIntervalMs)
        }

        return false
    }

    // ===============================
    // 并发最快站点返回
    // ===============================

    private suspend fun getNetworkTimeMultiSiteParallel(): Pair<String, String>? =
        coroutineScope {

            val deferredList = testUrls.map { site ->
                async(Dispatchers.IO) {
                    getNetworkTimeWithRetry(site)
                }
            }

            select<Pair<String, String>?> {
                deferredList.forEach { deferred ->
                    deferred.onAwait { result ->
                        if (result != null) {
                            deferredList.forEach { it.cancel() }
                            result
                        } else null
                    }
                }
            }
        }


    // ===============================
    // 单站点重试
    // ===============================

    suspend fun getNetworkTimeWithRetry(
        webUrl: String
    ): Pair<String, String>? {

        val timeouts = intArrayOf(3000, 5000, 8000, 10000, 12000)
        val maxRetries = timeouts.size

        repeat(maxRetries) { attempt ->

            var conn: HttpURLConnection? = null

            try {
                conn = (URL(webUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeouts[attempt.coerceAtMost(timeouts.lastIndex)]
                    readTimeout = timeouts[attempt.coerceAtMost(timeouts.lastIndex)]
                    connect()
                }

                val dateL = conn.date

                if (dateL > 0) {
                    val formatted = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date(dateL))

                    return formatted to dateL.toString()
                }

            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }

            if (attempt < maxRetries - 1) {
                delay((attempt + 1) * 2000L)
            }
        }

        return null
    }

    // ===============================
    // 防火墙检测
    // ===============================
    @JvmStatic
    @JvmOverloads
    suspend fun checkFirewallStatus(timeout: Int = 3000): NetStatus =
        withContext(Dispatchers.IO) {

            var reachableCount = 0
            var failureCount = 0

            testUrls.forEach { urlStr ->
                try {
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = timeout
                        readTimeout = timeout
                        connect()
                    }

                    if (conn.responseCode in 200..399) reachableCount++
                    else failureCount++

                    conn.disconnect()
                } catch (_: Exception) {
                    failureCount++
                }
            }

            when {
                reachableCount > 0 -> NetStatus.SERVER_OR_DNS_ERROR
                failureCount == testUrls.size -> NetStatus.MAYBE_BLOCKED_BY_FIREWALL
                else -> NetStatus.SERVER_OR_DNS_ERROR
            }
        }


    // ===============================
    // 网络变化自动清缓存
    // ===============================

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @JvmStatic
    @JvmOverloads
    fun registerNetworkListener(context: Context = appContext) {

        if (networkCallback != null) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                clearCache()
            }

            override fun onLost(network: Network) {
                clearCache()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                clearCache()
            }
        }

        cm.registerDefaultNetworkCallback(networkCallback!!)
    }
    @JvmStatic
    @JvmOverloads
    fun hasRequiredPermissions(context: Context = appContext): Boolean {
        val pm = context.packageManager

        val internet =
            context.checkCallingOrSelfPermission(
                android.Manifest.permission.INTERNET
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val networkState =
            context.checkCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return internet && networkState
    }

    /**
     * 极速外网可达检测（5秒缓存版）
     * 2 秒超时
     * 任意一个站点成功即返回 true
     */
    @JvmStatic
    suspend fun canAccessAnyExternalSiteFastCached(): Boolean = coroutineScope {

        val now = System.currentTimeMillis()

        // ===== ① 快速缓存命中 =====
        lastFastCheckResult?.let {
            if (now - lastFastCheckTime < FAST_CHECK_CACHE_DURATION) {
                return@coroutineScope it
            }
        }

        fastCheckMutex.withLock {

            // ===== 双重检查 =====
            lastFastCheckResult?.let {
                if (System.currentTimeMillis() - lastFastCheckTime < FAST_CHECK_CACHE_DURATION) {
                    return@coroutineScope it
                }
            }

            // ===== 系统网络能力检查 =====
            if (!isNetworkAvailable()) {
                lastFastCheckResult = false
                lastFastCheckTime = System.currentTimeMillis()
                return@coroutineScope false
            }

            // ===== 并发 HEAD 请求 =====
            val deferredList = testUrls.map { url ->
                async(Dispatchers.IO) {
                    try {
                        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                            requestMethod = "HEAD"
                            instanceFollowRedirects = false
                            connectTimeout = 2000
                            readTimeout = 2000
                            useCaches = false
                            connect()
                        }

                        val code = conn.responseCode
                        conn.disconnect()

                        code in 200..399

                    } catch (_: Exception) {
                        false
                    }
                }
            }

            val result = try {
                select<Boolean> {
                    deferredList.forEach { deferred ->
                        deferred.onAwait { success ->
                            if (success) {
                                deferredList.forEach { it.cancel() }
                                true
                            } else false
                        }
                    }
                }
            } finally {
                deferredList.forEach { it.cancel() }
            }

            // ===== 更新缓存 =====
            lastFastCheckResult = result
            lastFastCheckTime = System.currentTimeMillis()

            return@coroutineScope result
        }
    }

    /**
     * 极速网络时间获取（前提：已确认外网可访问）
     * 单站点顺序尝试
     * 每个站点最大 1500ms
     * 不重试
     */
    @JvmStatic
    @JvmOverloads
    suspend fun getNetworkTimeFast(pat: String ="yy-MM-dd HH:mm"): Pair<String, String>? =
        withContext(Dispatchers.IO) {

            for (url in testUrls) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        instanceFollowRedirects = false
                        connectTimeout = 1500
                        readTimeout = 1500
                        useCaches = false
                        connect()
                    }

                    val date = conn.date
                    if (date > 0) {
                        val formatted = SimpleDateFormat(
                            pat,
                            Locale.getDefault()
                        ).format(Date(date))

                        return@withContext formatted to date.toString()
                    }

                } catch (_: Exception) {
                } finally {
                    conn?.disconnect()
                }
            }

            return@withContext null
        }
    @JvmStatic
    suspend fun updateTimeFastIfInternetOk() {

        // ① 先快速确认外网
        if (!NetworkHelperFullSmart.canAccessAnyExternalSiteFastCached()) {
            return
        }

        // ② 再快速获取时间
        val result = NetworkHelperFullSmart.getNetworkTimeFast()

        result?.let {
            HYSJTimeSecurityManager.updateTrustedTime(
                networkTimestamp = it.second.toLong()
            )
        }
    }


    fun clearCache() {
        lastResult = null
        lastCheckTime = 0L
        runningJob?.cancel()
        runningJob = null
    }

}


