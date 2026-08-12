package com.google.android.accessibility.ext.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.google.android.accessibility.baoshi.TTSManager
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * 播放管理单例：封装 MediaPlayer，提供「顺序播放」、进度回调、播放列表持久化、
 * 音频焦点管理。本地 (content://) 与在线 (https://) 歌曲统一用 MediaPlayer 播放。
 * 不依赖前台服务，仅在应用前台可见时播放（简单播放需求）。
 *
 * 与小程序 mqd-bbx 的播放逻辑对齐：多首顺序播放、最后一首结束停止；在线歌曲优先走本地缓存、
 * 未缓存则后台下载到缓存目录（标记 已缓存 / 下载中）。
 */
object MusicManager {

    enum class PlayState { IDLE, PLAYING, PAUSED }

    interface MusicListener {
        /** 播放状态变化（state 与当前曲目 index） */
        fun onMusicState(state: PlayState, index: Int)

        /** 进度回调（毫秒） */
        fun onMusicProgress(positionMs: Long, durationMs: Long)

        /** 播放列表变化（合并后的列表与当前 index） */
        fun onMusicPlaylist(list: List<Song>, index: Int)

        /** 播放失败（MediaPlayer 报错或源不可用时回调，便于 UI 提示） */
        fun onMusicError(message: String) {}
    }

    private const val TAG = "MusicManager"

    private var player: MediaPlayer? = null
    private val listeners = LinkedHashSet<MusicListener>()
    private val handler = Handler(Looper.getMainLooper())

    private var playlist: List<Song> = emptyList()
    private var currentIndex = -1
    private var state = PlayState.IDLE
    private var notifiedIndex = -1
    private var lastError: String? = null
    /** 暂停时记录的位置（ms），用于「续播」时从暂停处开始。 */
    private var pausedPosition = 0
    /** 是否正处于 prepareAsync 阶段。 */
    private var preparing = false
    /** 准备完成后需要跳到的位置（ms）；0 表示从头。 */
    private var pendingSeek = 0

    // 音频焦点
    private var audioManager: AudioManager? = null
    /** 单一焦点请求：只创建一次并复用，避免每次 prepareAndPlay 都新建 → 旧请求被取代触发 LOSS 死循环 */
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false
    /** 准备过程中若需暂停（焦点丢失 / 用户中途暂停），准备完成后保持暂停、不要自动开播 */
    private var wantPauseWhenReady = false
    /** 焦点变化监听器（复用同一实例） */
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedByFocusLoss = (state == PlayState.PLAYING)
                internalPause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    resume()
                }
            }
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                try {
                    listeners.forEach {
                        it.onMusicProgress(
                            p.currentPosition.toLong(),
                            p.duration.toLong().coerceAtLeast(0)
                        )
                    }
                } catch (_: IllegalStateException) {
                    // 尚未 prepare 完成，忽略
                }
            }
            if (state == PlayState.PLAYING) handler.postDelayed(this, 500)
        }
    }

    private val preparedListener = MediaPlayer.OnPreparedListener {
        preparing = false
        // 准备过程中用户/焦点要求暂停：保持暂停，不自动开播
        if (wantPauseWhenReady) {
            wantPauseWhenReady = false
            Log.d(TAG, "onPrepared but wantPause, hold paused idx=$currentIndex")
            setState(PlayState.PAUSED)
            return@OnPreparedListener
        }
        if (pendingSeek > 0) {
            try { it.seekTo(pendingSeek) } catch (_: IllegalStateException) { }
            Log.d(TAG, "onPrepared seek to $pendingSeek idx=$currentIndex")
        }
        try {
            it.start()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "start failed: ${e.message}")
            return@OnPreparedListener
        }
        startProgress()
        setState(PlayState.PLAYING)
        lastError = null
        startVibrateIfNeeded() // 播放开关与「播放时震动」均开启时，启动循环震动（来电式节奏）
        speakTtsIfNeeded() // 提醒总开关与「TTS 播报」均开启、且有文字时，朗读自定义文字
        Log.d(TAG, "onPrepared -> start, idx=$currentIndex")
    }

    // 播放时震动（来电式节奏，循环持续到暂停/停止/播完）
    private var vibrator: Vibrator? = null
    private var vibrating = false
    /** 来电式震动节奏（ms）：静0 / 震250 / 停120 / 震250 / 停780，循环 → “嗡-嗡 … 嗡-嗡” */
    private val CALL_VIBRATE_PATTERN = longArrayOf(0, 250, 120, 250, 780)

    /** 起播即启动循环震动（仅当「播放开关」与「播放时震动」均开启）；已在震动则忽略，保证切歌/续播无缝连续 */
    private fun startVibrateIfNeeded() {
        if (!MusicStore.isBgmOn() || !MusicStore.isVibrateOn()) return
        if (vibrator == null) {
            vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        val v = vibrator ?: return
        if (vibrating) return
        vibrating = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(CALL_VIBRATE_PATTERN, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(CALL_VIBRATE_PATTERN, 0)
            }
        } catch (_: Exception) {
            vibrating = false
        }
    }

    /** 停止循环震动（暂停 / 停止 / 播完时调用） */
    private fun stopVibrate() {
        if (!vibrating) return
        vibrating = false
        try { vibrator?.cancel() } catch (_: Exception) { }
    }

    /** 外部（UI 震动开关）在播放过程中改变设置时调用：关闭 → 立即停震；开启 → 正在播放则立即开始震 */
    fun onVibrateSettingChanged(on: Boolean) {
        if (on) {
            if (state == PlayState.PLAYING) startVibrateIfNeeded()
        } else {
            stopVibrate()
        }
    }

    /** 外部（UI TTS 开关）改变设置时调用：开启 → 立即朗读自定义文字（即使歌曲列表为空，只要提醒总开关开启且有文字）；关闭 → 立即停读 */
    fun onTtsSettingChanged(on: Boolean) {
        if (on) speakTtsIfNeeded() else stopTts()
    }

    // 播放时 TTS 播报自定义文字（仅当「提醒总开关」与「TTS 播报」均开启、且文字非空）
    /** 每首歌起播时朗读自定义文字；已满足开关条件但文字为空则不播报 */
    private fun speakTtsIfNeeded() {
        if (!MusicStore.isBgmOn() || !MusicStore.isTtsOn()) return
        val text = MusicStore.getTtsText().trim()
        if (text.isEmpty()) return
        try {
            TTSManager.speak(appContext, text)
        } catch (_: Exception) {
        }
    }

    /** 停止当前 TTS 朗读（暂停 / 停止 / 播完时调用） */
    private fun stopTts() {
        try { TTSManager.stop() } catch (_: Exception) { }
    }

    private val completionListener = MediaPlayer.OnCompletionListener {
        Log.d(TAG, "onCompletion idx=$currentIndex")
        onSongEnded()
    }

    private val errorListener = MediaPlayer.OnErrorListener { _, what, extra ->
        val song = playlist.getOrNull(currentIndex)
        val msg = "播放失败：格式或源不支持 (what=$what extra=$extra)"
        Log.e(TAG, "$msg src=${song?.src}")
        lastError = msg
        listeners.forEach { it.onMusicError(msg) }
        // 当前曲目出错：跳到下一首，避免卡死；仅一首则停止
        if (playlist.size > 1) {
            play(if (currentIndex < playlist.size - 1) currentIndex + 1 else 0)
        } else {
            stop()
        }
        true
    }

    private fun ensurePlayer(): MediaPlayer {
        if (player == null) {
            val mp = MediaPlayer()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            mp.setAudioAttributes(attrs)
            mp.setOnPreparedListener(preparedListener)
            mp.setOnCompletionListener(completionListener)
            mp.setOnErrorListener(errorListener)
            player = mp
        }
        return player!!
    }

    /** 释放并置空当前 MediaPlayer，切歌时用于重建干净实例，避免复用残留的 Error/PlaybackCompleted 状态 */
    private fun releasePlayerInternal() {
        try {
            player?.let { it.stop(); it.reset(); it.release() }
        } catch (_: Exception) { }
        player = null
    }

    // ---------- 监听器 ----------
    fun addListener(l: MusicListener) {
        listeners.add(l)
        l.onMusicState(state, currentIndex)
        l.onMusicPlaylist(playlist, currentIndex)
    }

    fun removeListener(l: MusicListener) {
        listeners.remove(l)
    }

    private fun setState(s: PlayState) {
        // 状态字符串未变但曲目已切换（如切歌时上一首也是 PLAYING），也要通知 UI
        val indexChanged = currentIndex != notifiedIndex
        if (state == s && !indexChanged) return
        state = s
        notifiedIndex = currentIndex
        listeners.forEach { it.onMusicState(s, currentIndex) }
        if (s != PlayState.PLAYING) handler.removeCallbacks(progressRunnable)
    }

    private fun startProgress() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    fun getPlaylist(): List<Song> = playlist
    fun getState(): PlayState = state
    fun getCurrentIndex(): Int = currentIndex

    // ---------- 播放列表 ----------
    fun loadPlaylist(list: List<Song>, startIndex: Int = if (playlist.isEmpty()) -1 else currentIndex) {
        playlist = list
        MusicStore.save(list)
        val idx = if (list.isEmpty()) -1 else startIndex.coerceIn(0, list.size - 1)
        currentIndex = idx
        MusicStore.setPlayIndex(idx.coerceAtLeast(0))
        listeners.forEach { it.onMusicPlaylist(list, idx) }
    }

    /** 判定是否为同一首歌：在线按 URL、本地按 content:// URI，与歌名无关 */
    private fun isSameSong(a: Song, b: Song): Boolean = a.source == b.source && a.src == b.src

    fun addSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        // 去重：source+src 已存在的歌不再追加（在线=URL，本地=content URI）
        val incoming = songs.filter { new -> playlist.none { isSameSong(it, new) } }
        if (incoming.isEmpty()) return
        val startIdx = if (playlist.isEmpty()) 0 else currentIndex.coerceAtLeast(0)
        loadPlaylist(playlist + incoming, startIdx)
    }

    fun removeAt(index: Int) {
        if (index < 0 || index >= playlist.size) return
        val wasPlaying = index == currentIndex
        val list = playlist.toMutableList().apply { removeAt(index) }
        val newIdx = when {
            list.isEmpty() -> -1
            wasPlaying -> currentIndex.coerceAtMost(list.size - 1)
            index < currentIndex -> currentIndex - 1
            else -> currentIndex
        }
        loadPlaylist(list, newIdx)
        if (wasPlaying) {
            if (list.isNotEmpty() && state != PlayState.IDLE) play(newIdx) else stop()
        }
    }

    fun reorder(from: Int, to: Int) {
        if (from == to) return
        val list = playlist.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        currentIndex = when {
            from == currentIndex -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        loadPlaylist(list, currentIndex)
    }

    fun clearAll() {
        stop()
        loadPlaylist(emptyList(), -1)
    }

    // ---------- 播放控制 ----------
    fun play(index: Int) {
        if (playlist.isEmpty()) return
        val idx = index.coerceIn(0, playlist.size - 1)
        currentIndex = idx
        MusicStore.setPlayIndex(idx)
        // 立即通知 UI 当前曲目已切换（即便播放状态字符串未变），避免切歌后控件错位
        listeners.forEach { it.onMusicState(state, currentIndex) }
        val song = playlist[idx]
        lastError = null
        // 在线歌曲未缓存：先下载到本地再播。
        // 原因：MediaPlayer 对 HTTP 流式播放很挑剔（GitLab raw 返回 application/octet-stream +
        // Content-Disposition: attachment 会导致流式失败），且 FLAC 依赖设备解码器；
        // 下载到本地后走本地文件解码最稳妥，也契合「首次下载、之后离线」的设计。
        if (song.source == SongSource.ONLINE && !song.file.isNullOrEmpty() && !MusicCache.hasCached(song.file!!)) {
            updateState(song, SongCacheState.LOADING)
            listeners.forEach { it.onMusicPlaylist(playlist, idx) }
            MusicCache.cacheAsync(song) { ok ->
                if (ok) {
                    Log.d(TAG, "cache done, play local: ${song.file}")
                    updateState(song, SongCacheState.CACHED)
                    prepareAndPlay(song)
                } else {
                    Log.w(TAG, "cache failed, fallback to stream: ${song.src}")
                    updateState(song, SongCacheState.NONE)
                    prepareAndPlay(song) // 回退到直链流式（仍失败由 errorListener 处理）
                }
            }
            return
        }
        prepareAndPlay(song)
    }

    /** 真正开始准备并播放：本地缓存文件 / 本地 content:// / 在线直链兜底。
     *  @param seekToMs 准备完成后跳到的位置（ms）。续播场景用于从暂停处继续；0 表示从头。 */
    private fun prepareAndPlay(song: Song, seekToMs: Int = 0) {
        val uri = resolveUri(song)
        // 切换/重播时重建 MediaPlayer，规避复用旧实例可能残留的 Error / PlaybackCompleted 状态
        releasePlayerInternal()
        ensurePlayer()
        requestAudioFocus()
        val p = player!!
        pendingSeek = seekToMs
        preparing = true
        MusicStore.save(playlist)
        listeners.forEach { it.onMusicPlaylist(playlist, currentIndex) }
        Log.d(TAG, "prepareAndPlay idx=$currentIndex uri=$uri scheme=${uri.scheme} seek=$seekToMs")
        try {
            if (uri.scheme == "http" || uri.scheme == "https") {
                p.setDataSource(uri.toString())
            } else {
                p.setDataSource(appContext, uri)
            }
            p.prepareAsync()
        } catch (e: Exception) {
            preparing = false
            Log.e(TAG, "setDataSource failed: ${e.message}")
            if (playlist.size > 1) play(if (currentIndex < playlist.size - 1) currentIndex + 1 else 0) else stop()
        }
    }

    fun playList(list: List<Song>, startIndex: Int = 0) {
        loadPlaylist(list, startIndex)
        play(startIndex)
    }

    /**
     * 从本地持久化恢复上次保存的歌单并起播，**不依赖 MusicActivity 界面**。
     * 适用于「之前在界面里添加过歌单、之后不再打开界面、直接想继续播」的场景。
     * @return 是否成功开始（歌单为空则 false）
     */
    fun restoreAndPlay(): Boolean {
        // 内存中已有歌单（如界面打开过）：按已存序号直接起播
        if (playlist.isNotEmpty()) {
            play(MusicStore.getPlayIndex().coerceIn(0, playlist.size - 1))
            return true
        }
        val saved = MusicStore.load()
        if (saved.isEmpty()) {
            // 空歌单：若 TTS 播报开启且有文字，仍朗读自定义文字（即使没有歌）
            speakTtsIfNeeded()
            return true
        }
        val idx = MusicStore.getPlayIndex().coerceIn(0, saved.size - 1)
        loadPlaylist(saved, idx)
        play(idx)
        return true
    }

    fun togglePlay() {
        when (state) {
            PlayState.PLAYING -> pause()
            PlayState.PAUSED -> resume()
            PlayState.IDLE -> if (playlist.isNotEmpty()) play(0)
        }
    }

    fun pause() {
        internalPause()
        abandonAudioFocus() // 用户主动暂停：释放音频焦点，避免长期占用
    }

    /** 仅暂停播放（不释放播放器、不放弃焦点），供音频焦点丢失 / 系统中断时调用。
     *  若正处于准备阶段，则标记「准备完成后保持暂停」，避免在这里动 player 破坏 prepare。 */
    private fun internalPause() {
        stopVibrate()
        stopTts()
        if (preparing) {
            wantPauseWhenReady = true
            handler.removeCallbacks(progressRunnable)
            setState(PlayState.PAUSED)
            return
        }
        val p = player
        if (p != null && state == PlayState.PLAYING) {
            try { pausedPosition = p.currentPosition } catch (_: Exception) { }
            try { p.pause() } catch (_: IllegalStateException) { }
        }
        handler.removeCallbacks(progressRunnable)
        setState(PlayState.PAUSED)
    }

    fun resume() {
        if (playlist.isEmpty()) return
        if (currentIndex < 0) { play(0); return }
        // 续播：重建干净实例并从暂停位置 seek 继续（最可靠，本地/缓存几乎瞬时）
        prepareAndPlay(playlist[currentIndex.coerceAtLeast(0)], seekToMs = pausedPosition)
    }

    fun stop() {
        stopVibrate()
        stopTts()
        try {
            player?.let { it.stop(); it.reset() }
        } catch (_: IllegalStateException) { }
        abandonAudioFocus()
        currentIndex = -1
        notifiedIndex = -1
        preparing = false
        pendingSeek = 0
        pausedPosition = 0
        wantPauseWhenReady = false
        handler.removeCallbacks(progressRunnable)
        setState(PlayState.IDLE)
        listeners.forEach { it.onMusicState(PlayState.IDLE, -1) }
    }

    fun next() {
        if (playlist.isEmpty()) return
        play(if (currentIndex < playlist.size - 1) currentIndex + 1 else 0)
    }

    fun prev() {
        if (playlist.isEmpty()) return
        play(if (currentIndex > 0) currentIndex - 1 else playlist.size - 1)
    }

    fun seekTo(ms: Long) {
        try { player?.seekTo(ms.toInt()) } catch (_: IllegalStateException) { }
    }

    private fun onSongEnded() {
        val isLast = currentIndex >= playlist.size - 1
        // 顺序循环开 → 最后一首回到第一首；关 → 播完最后一首即停止。仅一首歌始终停止。
        if (playlist.size > 1 && (!isLast || MusicStore.isLoopOn())) {
            play(if (isLast) 0 else currentIndex + 1)
        } else {
            stop()
        }
    }

    private fun resolveUri(song: Song): Uri {
        if (song.source == SongSource.ONLINE && !song.file.isNullOrEmpty() && MusicCache.hasCached(song.file!!)) {
            return Uri.fromFile(MusicCache.localFile(song.file!!))
        }
        return Uri.parse(song.src)
    }

    private fun updateState(song: Song, st: SongCacheState) {
        val idx = playlist.indexOfFirst { it.src == song.src }
        if (idx < 0) return
        val cur = playlist[idx]
        if (cur.state == st) return
        playlist = playlist.toMutableList().apply { this[idx] = cur.copy(state = st) }
        MusicStore.save(playlist)
        listeners.forEach { it.onMusicPlaylist(playlist, currentIndex) }
    }

    // ---------- 音频焦点 ----------
    private fun requestAudioFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 复用单一 focusRequest，绝不每次新建（否则旧请求被取代会收到 LOSS → 误暂停）
            if (focusRequest == null) {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attr)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
            }
            am.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
        audioManager = null
        focusRequest = null
    }
}
