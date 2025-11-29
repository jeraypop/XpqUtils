package com.google.android.accessibility.ext.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.apply
import kotlin.collections.forEach
import kotlin.collections.lastIndex
import kotlin.collections.map
import kotlin.ranges.coerceAtMost
import kotlin.to

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2025/11/29  14:41
 * Description:This is NetworkCheckHelper
 */
object NetworkHelperFull {

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

    /**
     * 外部调用入口
     */
    @JvmOverloads
    @JvmStatic
    fun checkNetworkAndGetTime(context: Context = appContext): NetworkCheckResult {
        if (!isNetworkAvailable(context)) {
            return NetworkCheckResult(NetStatus.NETWORK_UNAVAILABLE)
        }

        val timeResult = getNetworkTimeMultiSiteParallel()

        return if (timeResult != null) {
            NetworkCheckResult(NetStatus.INTERNET_OK, timeResult.first, timeResult.second)
        } else {
            val status = checkFirewallStatus()
            NetworkCheckResult(status)
        }
    }


    /**
     * 根据外网访问判断是否被防火墙禁网 / DNS / 手机管家限制
     */
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
                if (conn.responseCode in 200..399) reachableCount++ else failureCount++
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


    /**
     * 系统网络状态检查
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }


    /**
     * 并发最快站点返回网络时间
     */
    private fun getNetworkTimeMultiSiteParallel(): Pair<String, String>? {
        val executor = Executors.newFixedThreadPool(testUrls.size)
        val futures = testUrls.map { site ->
            executor.submit<Pair<String, String>?> { getNetworkTimeWithRetry(site) }
        }

        return try {
            for (future in futures) {
                try {
                    val r = future.get(15, TimeUnit.SECONDS)
                    if (r != null) {
                        futures.forEach { it.cancel(true) }
                        return r
                    }
                } catch (_: Exception) { }
            }
            null
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * 单站点重试
     */
    private fun getNetworkTimeWithRetry(webUrl: String): Pair<String, String>? {
        val maxRetries = 3
        val timeouts = intArrayOf(3000, 5000, 8000)

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
                    val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(dateL))
                    //sendLog("$webUrl 当前时间 = $formatted")
                    return formatted to dateL.toString()
                }

            } catch (e: Exception) {
                //sendLog("第${attempt + 1}次尝试 $webUrl 错误：$e")
            } finally {
                conn?.disconnect()
            }

            if (attempt < maxRetries - 1) {
                Thread.sleep(((attempt + 1) * 2000).toLong())
            }
        }

        return null
    }

}


