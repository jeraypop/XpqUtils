package com.google.android.accessibility.ext.music

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.media.MediaPlayer
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.accessibility.ext.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 在线曲库多选弹窗（仿小程序 music 选曲弹窗）：
 *  - 从 GitLab songs/ 动态拉取目录（同 MusicRepository）
 *  - 每行：勾选框 / 歌名 / 缓存徽标 / 试听按钮
 *  - 支持试听（独立 MediaPlayer，不打断主播放列表）
 *  - 「立即缓存」批量下载、「清理缓存」清除本地
 *  - 确定后把勾选的歌曲回传给宿主 Activity
 */
class OnlinePickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "OnlinePicker"
    }

    /** 确定选择后回调，返回勾选的在线歌曲 */
    var onConfirm: ((List<Song>) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var songs: List<Song> = emptyList()
    private val selected = LinkedHashSet<String>() // file 名（本次新勾选）
    private val added = LinkedHashSet<String>() // file 名（已在播放列表中的在线歌曲）
    private val stateMap = mutableMapOf<String, SongCacheState>()

    private var previewPlayer: MediaPlayer? = null
    private var previewFile: String? = null
    private var previewPlaying = false
    private var previewDownloading = false

    private lateinit var adapter: PickerAdapter
    private lateinit var titleView: TextView
    private lateinit var confirmBtn: TextView

    override fun getTheme(): Int = R.style.Theme_XpqMusic_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_online_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.tv_picker_title)
        confirmBtn = view.findViewById(R.id.btn_picker_confirm)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_picker)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PickerAdapter(
            onToggle = { file -> toggleSelect(file) },
            onPreview = { file, src -> togglePreview(file, src) }
        )
        recycler.adapter = adapter

        view.findViewById<View>(R.id.btn_picker_close).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_picker_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_picker_confirm).setOnClickListener {
            val chosen = songs.filter { it.file != null && it.file in selected }
            onConfirm?.invoke(chosen)
            dismiss()
        }
        view.findViewById<View>(R.id.btn_cache_now).setOnClickListener { cacheAll() }
        view.findViewById<View>(R.id.btn_clear_cache).setOnClickListener { clearCache() }

        loadSongs()
        updateConfirmText()
    }

    override fun onDestroyView() {
        stopPreview()
        previewPlayer?.release()
        previewPlayer = null
        super.onDestroyView()
    }

    private fun loadSongs() {
        titleView.text = getString(R.string.music_picker_title) + "（" + getString(R.string.music_loading) + "）"
        scope.launch {
            val list = MusicRepository.fetchOnlineSongs()
            songs = list
            // 标记已在播放列表中的在线歌曲（按 file 名），弹窗内显示「已添加」并禁止重复勾选
            added.clear()
            added.addAll(
                MusicManager.getPlaylist()
                    .filter { it.source == SongSource.ONLINE }
                    .mapNotNull { it.file }
            )
            list.forEach { s ->
                if (!s.file.isNullOrEmpty()) {
                    stateMap[s.file!!] = if (MusicCache.hasCached(s.file!!)) SongCacheState.CACHED else SongCacheState.NONE
                }
            }
            adapter.submit(list, selected.toList(), stateMap, added.toList())
            titleView.text = getString(R.string.music_picker_title) + "（共 ${list.size} 首）"
            updateConfirmText()
            // 弹窗出现即可自动把未缓存的在线歌后台下载（用户无需点「立即缓存」），
            // 下载完成后徽标会从「下载中」自动变为「已缓存」。已缓存的会跳过。
            autoCacheOnOpen()
        }
    }

    /** 弹窗打开即自动缓存全部未缓存的在线歌曲（后台下载，不阻塞 UI） */
    private fun autoCacheOnOpen() {
        val toCache = songs.filter { it.file != null && !MusicCache.hasCached(it.file!!) }
        if (toCache.isEmpty()) return
        Log.d(TAG, "auto cache on open: ${toCache.size} songs")
        cacheAll()
    }

    private fun toggleSelect(file: String) {
        if (added.contains(file)) return // 已添加的不允许重复勾选（主列表去重会丢弃）
        if (selected.contains(file)) selected.remove(file) else selected.add(file)
        adapter.setSelected(selected.toList())
        updateConfirmText()
    }

    private fun updateConfirmText() {
        confirmBtn.text = getString(R.string.music_picker_confirm).let {
            // 保持文案「确定（已选 N）」格式
            "确定（已选 ${selected.size}）"
        }
    }

    private fun cacheAll() {
        val toCache = songs.filter { it.file != null && !MusicCache.hasCached(it.file!!) }
        if (toCache.isEmpty()) {
            adapter.notifyCacheChanged(stateMap)
            return
        }
        toCache.forEach { s ->
            val f = s.file!!
            stateMap[f] = SongCacheState.LOADING
        }
        adapter.notifyCacheChanged(stateMap)
        MusicCache.cacheAll(toCache) { song, ok ->
            song.file?.let { stateMap[it] = if (ok) SongCacheState.CACHED else SongCacheState.NONE }
            adapter.notifyCacheChanged(stateMap)
        }
    }

    private fun clearCache() {
        MusicCache.clearAll()
        songs.forEach { s -> s.file?.let { stateMap[it] = SongCacheState.NONE } }
        adapter.notifyCacheChanged(stateMap)
    }

    // ---------- 试听（独立 MediaPlayer，不干扰主列表） ----------
    private fun ensurePreviewPlayer(): MediaPlayer {
        if (previewPlayer == null) {
            previewPlayer = MediaPlayer()
        }
        return previewPlayer!!
    }

    private fun togglePreview(file: String, src: String) {
        val p = ensurePreviewPlayer()
        // 正在试听或正在下载 → 停止
        if (previewFile == file && (previewPlaying || previewDownloading)) {
            stopPreview()
            adapter.setPreview(file, false)
            return
        }
        val isOnline = src.startsWith("http", ignoreCase = true)
        val cached = isOnline && MusicCache.hasCached(file)
        // 在线未缓存：先下载到本地再试听（MediaPlayer 无法稳定流式播放 octet-stream/FLAC）
        if (isOnline && !cached) {
            val song = songs.firstOrNull { it.file == file }
            if (song != null) {
                previewDownloading = true
                previewFile = file
                previewPlaying = false
                adapter.setPreview(file, true)
                MusicCache.cacheAsync(song) { ok ->
                    previewDownloading = false
                    if (previewFile != file) return@cacheAsync // 用户已切歌/停止
                    if (ok) {
                        playPreviewLocal(file, Uri.fromFile(MusicCache.localFile(file)))
                    } else {
                        playPreviewLocal(file, Uri.parse(src))
                    }
                }
                return
            }
        }
        val uri = if (cached) Uri.fromFile(MusicCache.localFile(file)) else Uri.parse(src)
        playPreviewLocal(file, uri)
    }

    private fun playPreviewLocal(file: String, uri: Uri) {
        val p = ensurePreviewPlayer()
        try {
            p.reset()
            p.setOnPreparedListener { mp ->
                try { mp.start() } catch (_: IllegalStateException) { }
            }
            p.setOnCompletionListener {
                previewPlaying = false
                adapter.setPreview(file, false)
            }
            p.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "preview error what=$what extra=$extra file=$file")
                previewPlaying = false
                previewFile = null
                adapter.setPreview(file, false)
                true
            }
            if (uri.scheme == "http" || uri.scheme == "https") {
                p.setDataSource(uri.toString())
            } else {
                p.setDataSource(requireContext(), uri)
            }
            p.prepareAsync()
            previewFile = file
            previewPlaying = true
            adapter.setPreview(file, true)
        } catch (e: Exception) {
            Log.e(TAG, "preview setDataSource failed: ${e.message}")
            previewPlaying = false
            previewFile = null
            adapter.setPreview(file, false)
        }
    }

    private fun stopPreview() {
        try { previewPlayer?.stop() } catch (_: IllegalStateException) { }
        try { previewPlayer?.reset() } catch (_: IllegalStateException) { }
        previewPlaying = false
        previewDownloading = false
        previewFile = null
    }

    // ---------- 适配器 ----------
    private class PickerAdapter(
        private val onToggle: (String) -> Unit,
        private val onPreview: (String, String) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.VH>() {

        private var list: List<Song> = emptyList()
        private var selected: List<String> = emptyList()
        private var added: List<String> = emptyList()
        private var states: Map<String, SongCacheState> = emptyMap()
        private var previewFile: String? = null
        private var previewing = false

        fun submit(list: List<Song>, selected: List<String>, states: Map<String, SongCacheState>, added: List<String>) {
            this.list = list
            this.selected = selected
            this.added = added
            this.states = states
            notifyDataSetChanged()
        }

        fun setSelected(s: List<String>) {
            selected = s
            notifyDataSetChanged()
        }

        fun setPreview(file: String?, playing: Boolean) {
            previewFile = file
            previewing = playing
            notifyDataSetChanged()
        }

        fun notifyCacheChanged(states: Map<String, SongCacheState>) {
            this.states = states
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_picker_song, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val song = list[pos]
            val file = song.file ?: ""
            val isAdded = added.contains(file)
            val sel = selected.contains(file) || isAdded
            h.name.text = song.name
            h.check.setBackgroundResource(if (sel) R.drawable.bg_check_sel else R.drawable.bg_check)
            // 缓存徽标
            val st = states[file]
            when (st) {
                SongCacheState.CACHED -> {
                    h.cache.visibility = View.VISIBLE
                    h.cache.text = "已缓存"
                    h.cache.setTextColor(h.itemView.context.getColor(R.color.music_badge_cached))
                }
                SongCacheState.LOADING -> {
                    h.cache.visibility = View.VISIBLE
                    h.cache.text = "下载中"
                    h.cache.setTextColor(h.itemView.context.getColor(R.color.music_badge_loading))
                }
                else -> h.cache.visibility = View.GONE
            }
            // 已添加徽标
            if (isAdded) {
                h.added.visibility = View.VISIBLE
            } else {
                h.added.visibility = View.GONE
            }
            // 试听按钮
            val previewOn = previewing && previewFile == file
            h.preview.text = if (previewOn) "⏸" else "▶"
            h.preview.setBackgroundResource(if (previewOn) R.drawable.bg_play_on else R.drawable.bg_play_off)
            h.preview.setOnClickListener { onPreview(file, song.src) }
            h.itemView.setOnClickListener { onToggle(file) }
        }

        override fun getItemCount(): Int = list.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tv_picker_name)
            val check: View = v.findViewById(R.id.picker_check)
            val cache: TextView = v.findViewById(R.id.tv_picker_cache)
            val added: TextView = v.findViewById(R.id.tv_picker_added)
            val preview: TextView = v.findViewById(R.id.btn_picker_preview)
        }
    }
}
