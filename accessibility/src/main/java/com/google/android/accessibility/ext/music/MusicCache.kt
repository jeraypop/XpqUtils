package com.google.android.accessibility.ext.music

import android.util.Log
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Semaphore

/**
 * 在线歌曲本地缓存：把 GitLab songs/ 下的音频下载到应用缓存目录，
 * 之后优先播放本地文件（与小程序「首次下载、之后离线」逻辑一致）。
 * 使用 HttpURLConnection，避免引入额外网络依赖（与 MusicRepository 风格一致）。
 */
object MusicCache {
    private const val TAG = "MusicCache"

    /**
     * 同时下载的最大并发数：避免在线曲库歌曲很多时一次性拉起几十个连接，
     * 既省带宽也规避部分机型/系统对并发请求数、连接池的限制导致大量失败。
     */
    private const val MAX_CONCURRENT_DOWNLOADS = 3

    /** 下载并发信号量：同时只有 [MAX_CONCURRENT_DOWNLOADS] 个下载在跑，其余排队。 */
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    private fun dir(): File {
        val d = File(appContext.cacheDir, "music_songs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun localFile(file: String): File = File(dir(), file)

    fun hasCached(file: String): Boolean = file.isNotEmpty() && localFile(file).exists()

    /** 异步下载某首在线歌曲到缓存目录；结果通过 onDone 回主线程 */
    fun cacheAsync(song: Song, onDone: (Boolean) -> Unit) {
        val file = song.file ?: return onDone(false)
        if (hasCached(file)) return onDone(true)
        CoroutineScope(Dispatchers.IO).launch {
            val ok = download(song.src, file)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** 批量下载（供「立即缓存」使用），并发数受 [MAX_CONCURRENT_DOWNLOADS] 限制 */
    fun cacheAll(songs: List<Song>, onEach: (Song, Boolean) -> Unit) {
        songs.forEach { s ->
            val file = s.file ?: return@forEach
            if (hasCached(file)) return@forEach
            CoroutineScope(Dispatchers.IO).launch {
                // 受信号量约束：同时最多 MAX_CONCURRENT_DOWNLOADS 个下载在跑，其余排队（acquire 阻塞当前 IO 线程，不会空转）
                downloadSemaphore.acquire()
                try {
                    val ok = download(s.src, file)
                    withContext(Dispatchers.Main) { onEach(s, ok) }
                } finally {
                    downloadSemaphore.release()
                }
            }
        }
    }

    private fun download(src: String, file: String): Boolean = try {
        val conn = (URL(src).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("Accept", "*/*")
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            false
        } else {
            val out = localFile(file)
            conn.inputStream.use { input ->
                out.outputStream().use { o -> input.copyTo(o) }
            }
            conn.disconnect()
            true
        }
    } catch (e: Exception) {
        Log.e(TAG, "cache $file failed: ${e.message}")
        false
    }

    fun clearAll() {
        try {
            dir().listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
