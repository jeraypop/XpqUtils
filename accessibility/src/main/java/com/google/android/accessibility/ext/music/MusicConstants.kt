package com.google.android.accessibility.ext.music

import java.util.regex.Pattern

/**
 * 与小程序 mqd-bbx 的 timer.js 顶部常量保持一致：
 * 在线曲库 = GitLab 仓库 songs/ 目录，列表用 repository/tree API 拉取，
 * 播放地址 = SONG_BASE + 文件名（普通 raw 直链）。注意该直链返回 Content-Type 为
 * application/octet-stream 且 Content-Disposition: attachment，MediaPlayer 直接流式播放易失败，
 * 故实际播放前会先下载到本地缓存再以本地文件播放（详见 MusicManager.play）。
 *
 * 如需换成自己的曲库，直接改下面两个字段即可（项目路径需 URL 编码，如 mygroup%2Fmyrepo）。
 */
object MusicConstants {
    /** 在线曲库歌曲基础地址（每个文件名拼到后面就是播放地址） */
    var SONG_BASE: String = "https://gitlab.com/mytiper/aczhu/-/raw/master/songs/"

    /** 列出 songs/ 目录下文件的 GitLab API */
    var SONG_API: String =
        "https://gitlab.com/api/v4/projects/mytiper%2Faczhu/repository/tree?path=songs&ref=master&per_page=100"

    /** 支持的音频后缀（与小程序选歌一致） */
    val SONG_EXTS: List<String> = listOf("mp3", "m4a", "wav", "aac", "flac", "ogg")

    /** 音频文件后缀正则（忽略大小写），用于过滤 GitLab 文件列表 */
    val SONG_EXT_RE: Pattern = Pattern.compile(
        "\\.(${SONG_EXTS.joinToString("|")})$",
        Pattern.CASE_INSENSITIVE
    )
}
