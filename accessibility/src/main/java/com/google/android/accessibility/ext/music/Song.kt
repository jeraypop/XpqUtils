package com.google.android.accessibility.ext.music

/** 歌曲来源：在线（与小程序同源）或本地（本机选取，不再从微信获取） */
enum class SongSource { ONLINE, LOCAL }

/** 在线歌曲缓存状态（当前实现直接流式播放，状态用于 UI 展示） */
enum class SongCacheState { NONE, LOADING, CACHED }

/**
 * 一首歌的数据模型，平移自小程序 timer.js 的 playlist 项。
 *
 * @param name   展示名（去后缀）
 * @param src    播放地址：在线 = https://... 直链；本地 = content://... 或 file://...
 * @param source 来源 ONLINE / LOCAL
 * @param file   仅在线：文件名，如 song1.mp3（用于去重）
 * @param state  仅在线：缓存状态
 */
data class Song(
    val name: String,
    val src: String,
    val source: SongSource,
    val file: String? = null,
    val state: SongCacheState = SongCacheState.NONE
)
