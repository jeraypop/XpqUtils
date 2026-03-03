package com.google.android.accessibility.ext.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*

object NetworkHelperFull {

    private val testUrls = listOf(
        "https://www.baidu.com",
        "https://www.taobao.com",
        "https://www.jd.com",
        "https://www.qq.com"
    )

    /**
     * 极简高效线程池
     * 核心线程=2，最大=4，避免过度并发
     */
    private val executor: ExecutorService by lazy {
        ThreadPoolExecutor(
            2,
            4,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    private val sdf by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    @JvmOverloads
    @JvmStatic
    fun checkNetworkAndGetTime(context: Context = appContext)
            : NetworkHelperFullSmart.NetworkCheckResult {

        if (!isNetworkAvailable(context)) {
            return NetworkHelperFullSmart.NetworkCheckResult(
                NetworkHelperFullSmart.NetStatus.NETWORK_UNAVAILABLE
            )
        }

        val result = getNetworkTimeFastest()

        return if (result != null) {
            NetworkHelperFullSmart.NetworkCheckResult(
                NetworkHelperFullSmart.NetStatus.INTERNET_OK,
                result.first,
                result.second
            )
        } else {
            NetworkHelperFullSmart.NetworkCheckResult(checkFirewallFast())
        }
    }

    /**
     * 极限最快返回
     * invokeAny 自动返回第一个成功结果
     */
    private fun getNetworkTimeFastest(): Pair<String, String>? {

        val tasks = testUrls.map { url ->
            Callable<Pair<String, String>?> {
                getNetworkTimeWithRetry(url)
            }
        }

        return try {
            executor.invokeAny(tasks, 15, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 单站点重试（缩短延迟）
     */
    private fun getNetworkTimeWithRetry(webUrl: String): Pair<String, String>? {

        val timeouts = intArrayOf(2000, 4000, 6000)

        repeat(timeouts.size) { attempt ->

            var conn: HttpURLConnection? = null

            try {
                conn = (URL(webUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeouts[attempt]
                    readTimeout = timeouts[attempt]
                    useCaches = false
                    instanceFollowRedirects = true
                    requestMethod = "HEAD"   // 🚀 只取头部，更快
                    connect()
                }

                val dateL = conn.date
                if (dateL > 0) {
                    return sdf.format(Date(dateL)) to dateL.toString()
                }

            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }

        return null
    }

    /**
     * 极快防火墙判断
     * 任意成功即返回
     */
    private fun checkFirewallFast(timeout: Int = 2500)
            : NetworkHelperFullSmart.NetStatus {

        val completionService =
            ExecutorCompletionService<Boolean>(executor)

        testUrls.forEach { url ->
            completionService.submit {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = timeout
                        readTimeout = timeout
                        useCaches = false
                        requestMethod = "HEAD"
                        connect()
                    }
                    val ok = conn.responseCode in 200..399
                    conn.disconnect()
                    ok
                } catch (_: Exception) {
                    false
                }
            }
        }

        repeat(testUrls.size) {
            try {
                if (completionService.take().get()) {
                    return NetworkHelperFullSmart.NetStatus.SERVER_OR_DNS_ERROR
                }
            } catch (_: Exception) {
            }
        }

        return NetworkHelperFullSmart.NetStatus.MAYBE_BLOCKED_BY_FIREWALL
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}