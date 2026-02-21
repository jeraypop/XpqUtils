package com.google.android.accessibility.ext.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/11/29  14:41
 * Description:This is NetworkCheckHelper
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

    @JvmStatic
    @JvmOverloads
    fun updateMyTime(interval: Long = CACHE_DURATION) {
        if (intervalIsDuan(interval))return
        CoroutineScope(Dispatchers.Main).launch {
            val result = NetworkHelperFullSmart.checkNetworkAndGetTimeSmart()
            if (result.status ==
                NetworkHelperFullSmart.NetStatus.INTERNET_OK
                && result.timestamp != null
            ) {
                HYSJTimeSecurityManager.updateTrustedTime(
                    networkTimestamp = result.timestamp.toLong()
                )
            }
        }
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

    private suspend fun realCheckNetwork(
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

    private fun isNetworkAvailable(context: Context = appContext): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ===============================
    // 系统网络检测 + 等待准备
    // ===============================
    private suspend fun waitForNetworkReady(
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

            try {
                deferredList.firstNotNullOfOrNull { deferred ->
                    try {
                        deferred.await()
                    } catch (_: Exception) {
                        null
                    }
                }
            } finally {
                deferredList.forEach { it.cancel() }
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

    private fun checkFirewallStatus(timeout: Int = 3000): NetStatus {

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

        return when {
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


    fun clearCache() {
        lastResult = null
        lastCheckTime = 0L
        runningJob?.cancel()
        runningJob = null
    }

}


