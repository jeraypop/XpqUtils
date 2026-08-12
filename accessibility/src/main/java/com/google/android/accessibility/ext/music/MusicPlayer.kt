package com.google.android.accessibility.ext.music

import android.content.Context
import android.content.Intent

/**
 * 宿主应用调用入口（供外部 App / 宿主拉起音乐功能）。
 *
 * 用法示例：
 * ```
 * // 打开选曲界面
 * MusicPlayer.openMusic(context)
 *
 * // 直接用一批歌曲起播
 * MusicPlayer.play(context, songs)
 *
 * // 恢复并播放上次保存的歌单（空歌单时若 TTS 播报开启也会朗读自定义文字）
 * MusicPlayer.playSaved(context)
 *
 * // 停止
 * MusicPlayer.stop(context)
 * ```
 */
object MusicPlayer {
    /** 宿主可直接用此 action 通过隐式 Intent 拉起选曲界面 */
    const val ACTION_OPEN_MUSIC = "com.google.android.accessibility.ext.action.OPEN_MUSIC"

    /** 打开音乐选曲界面（自动加 NEW_TASK，可在非 Activity 上下文调用） */
    @JvmStatic
    fun openMusic(context: Context) {
        val intent = Intent(context, MusicActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 用给定歌单起播（受「播放总开关」控制：总开关关闭时不播放） */
    @JvmStatic
    fun play(context: Context, songs: List<Song>, startIndex: Int = 0) {
        if (!MusicStore.isBgmOn()) return
        MusicManager.playList(songs, startIndex)
    }

    /**
     * 恢复并播放上次保存的歌单（无需打开 MusicActivity 界面）。
     * 例如：之前在界面里添加过歌曲并退出，之后想直接继续播、又不想再弹界面时使用。
     * 受「播放总开关」控制：总开关关闭时返回 false，不播放。
     * 若本地无保存歌单（空歌单），但「TTS 播报」开关开启且有自定义文字，仍会朗读该文字。
     * @return 是否成功开始（总开关关闭则 false）
     */
    @JvmStatic
    fun playSaved(context: Context): Boolean {
        if (!MusicStore.isBgmOn()) return false
        return MusicManager.restoreAndPlay()
    }

    /** 停止播放 */
    @JvmStatic
    fun stop(context: Context) {
        MusicManager.stop()
    }

    /** 当前是否正在播放 */
    @JvmStatic
    fun isPlaying(): Boolean = MusicManager.getState() == MusicManager.PlayState.PLAYING

    /** 当前播放的歌曲（无则返回 null） */
    @JvmStatic
    fun currentSong(): Song? = MusicManager.getPlaylist().getOrNull(MusicManager.getCurrentIndex())
}
