package com.google.android.accessibility.ext.music

import android.util.Log
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线曲库数据层：与小程序一致地拉取 GitLab songs/ 目录文件列表，
 * 过滤出音频文件并拼出播放直链，构建 [Song] 列表。
 */
object MusicRepository {

    private const val TAG = "MusicRepository"

    /** 拉取在线歌曲列表（失败返回空列表，UI 提示「暂无歌曲」） */
    suspend fun fetchOnlineSongs(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(MusicConstants.SONG_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                Log.w(TAG, "fetchOnlineSongs: HTTP ${conn.responseCode}")
                return@withContext emptyList()
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            parse(text)
        } catch (e: Exception) {
            Log.e(TAG, "fetchOnlineSongs failed: ${e.message}")
            emptyList()
        }
    }

    private fun parse(json: String): List<Song> {
        return try {
            val type = object : TypeToken<List<GitlabNode>>() {}.type
            val nodes: List<GitlabNode>? = Gson().fromJson(json, type)
            (nodes ?: emptyList())
                .filter { it.type == "blob" && it.name != null && MusicConstants.SONG_EXT_RE.matcher(it.name!!).find() }
                .sortedBy { it.name }
                .map { n ->
                    val file = n.name!!
                    Song(
                        name = file.replace(MusicConstants.SONG_EXT_RE.toRegex(), ""),
                        src = MusicConstants.SONG_BASE + file,
                        source = SongSource.ONLINE,
                        file = file
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}")
            emptyList()
        }
    }

    /** GitLab repository/tree API 返回的节点结构（仅取需要的字段） */
    private data class GitlabNode(val name: String?, val type: String?, val path: String?)
}
