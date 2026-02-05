@file:Suppress("MemberVisibilityCanBePrivate")

package com.example.videoparser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object ShortVideoParser {

    // =========================
    // OkHttp 5 Client
    // =========================
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120"

    // =========================
    // 对外入口
    // =========================
    fun parseNoWatermarkUrl(shareUrl: String): String? {
        val realUrl = resolveRedirectUrl(shareUrl) ?: return null
        val html = loadHtml(realUrl)

        return when {
            realUrl.contains("douyin", true) ->
                parseDouyin(html)
            realUrl.contains("kuaishou", true) ->
                parseKuaishou(html)
            else -> null
        }
    }

    // =========================
    // 302 跳转解析
    // =========================
    private fun resolveRedirectUrl(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                return response.header("Location")
            }
        }
        return null
    }

    // =========================
    // 加载 HTML
    // =========================
    private fun loadHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://www.douyin.com/")
            .build()

        client.newCall(request).execute().use {
            return it.body?.string().orEmpty()
        }
    }

    // =========================
    // 抖音解析（Gson）
    // =========================
    private fun parseDouyin(html: String): String? {
        return try {
            val pattern = Pattern.compile(
                "<script id=\"RENDER_DATA\".*?>(.*?)</script>",
                Pattern.DOTALL
            )
            val matcher = pattern.matcher(html)
            if (!matcher.find()) return null

            val encodedJson = matcher.group(1)
            val decoded = URLDecoder.decode(encodedJson, "UTF-8")

            val root = JsonParser.parseString(decoded).asJsonObject

            root.getAsJsonObject("aweme")
                .getAsJsonObject("detail")
                .getAsJsonObject("video")
                .getAsJsonObject("playAddr")
                .getAsJsonArray("urlList")
                .get(0)
                .asString

        } catch (e: Exception) {
            null
        }
    }

    // =========================
    // 快手解析（Gson）
    // =========================
    private fun parseKuaishou(html: String): String? {
        return try {
            val pattern =
                Pattern.compile("\"playUrl\":\"(https:\\\\?/\\\\?/[^\"\\\\]+)\"")
            val matcher = pattern.matcher(html)
            if (!matcher.find()) return null

            matcher.group(1)
                .replace("\\/", "/")
        } catch (e: Exception) {
            null
        }
    }
}
