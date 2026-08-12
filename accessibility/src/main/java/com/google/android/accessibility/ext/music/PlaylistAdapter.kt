package com.google.android.accessibility.ext.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.android.accessibility.ext.R

/**
 * 播放列表适配器：每行一首歌，左侧播放/暂停状态、歌名、来源标签（在线/本地）、右侧删除。
 * 当前正在播放的歌曲整行高亮（gold 描边）。点击整行 = 切换该首歌的播放/暂停。
 */
class PlaylistAdapter(
    private val onPlayToggle: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private val items = mutableListOf<Song>()
    private var currentIndex = -1
    private var playing = false

    fun submit(list: List<Song>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun getItems(): List<Song> = items.toList()

    /** 长按拖动排序时调用，仅移动内存中的顺序并刷新移动动画 */
    fun move(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        if (from == to) return
        val s = items.removeAt(from)
        items.add(to, s)
        notifyItemMoved(from, to)
    }

    /** 当前播放曲目 / 播放状态变化时刷新高亮 */
    fun setCurrent(index: Int, isPlaying: Boolean) {
        val old = currentIndex
        currentIndex = index
        playing = isPlaying
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_song, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val song = items[pos]
        val isCurrent = pos == currentIndex
        h.name.text = song.name
        val online = song.source == SongSource.ONLINE
        h.tag.text = if (online) "在线" else "本地"
        h.tag.setBackgroundResource(if (online) R.drawable.bg_tag_online else R.drawable.bg_tag_local)
        h.tag.setTextColor(
            if (online) h.itemView.context.getColor(R.color.music_tag_online)
            else h.itemView.context.getColor(R.color.music_tag_local)
        )
        // 在线歌曲且本地已缓存：显示「已缓存」徽标
        val cached = online && song.file != null && MusicCache.hasCached(song.file!!)
        h.cached.visibility = if (cached) View.VISIBLE else View.GONE
        h.playState.text = if (isCurrent && playing) "⏸" else "▶"
        h.root.setBackgroundResource(if (isCurrent) R.drawable.bg_item_active else R.drawable.bg_card)
        h.root.setOnClickListener { onPlayToggle(h.bindingAdapterPosition) }
        h.delete.setOnClickListener { onDelete(h.bindingAdapterPosition) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val playState: android.widget.TextView = v.findViewById(R.id.tv_play_state)
        val name: android.widget.TextView = v.findViewById(R.id.tv_name)
        val tag: android.widget.TextView = v.findViewById(R.id.tv_tag)
        val cached: android.widget.TextView = v.findViewById(R.id.tv_cached)
        val delete: android.widget.TextView = v.findViewById(R.id.tv_delete)
    }
}
