package com.google.android.accessibility.ext.music

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.android.accessibility.ext.R
import com.google.android.accessibility.ext.activity.XpqBaseActivity
import com.google.android.accessibility.baoshi.TTSManager
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext

/**
 * 音乐选曲界面（仿小程序 mqd-bbx 的选曲体验）：
 *  - 背景音乐开关（仅开启时显示播放列表，与小程序一致）
 *  - 播放列表：每行一首歌，点该行 = 切换该首的播放/暂停（暂停后点同一首会从原位置继续）；
 *    右侧可删除、长按可拖动排序（顺序循环）。没有独立的"上一首/下一首"大控制卡片。
 *  - 「本地选择」走系统 SAF 文档选择器（不再从微信获取）
 *  - 「在线曲库」走与小程序同源的 GitLab 在线曲库（多选 / 试听 / 缓存）
 */
class MusicActivity : XpqBaseActivity<ViewBinding>(layoutId = R.layout.activity_music),
    MusicManager.MusicListener {

    private lateinit var switch: SwitchCompat
    private lateinit var playSwitch: SwitchCompat
    private lateinit var vibrateSwitch: SwitchCompat
    private lateinit var vibrateDurationInput: EditText
    private lateinit var vibrateTestBtn: View
    private lateinit var loopSwitch: SwitchCompat
    private lateinit var ttsSwitch: SwitchCompat
    private lateinit var ttsInput: EditText
    private lateinit var ttsTestBtn: View
    private lateinit var tracksArea: View
    private lateinit var tipText: TextView
    private lateinit var adapter: PlaylistAdapter

    private var bgmOn = false
    private var lastError: String? = null

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = if (data.clipData != null) {
            val clip = data.clipData!!
            (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        } else {
            listOfNotNull(data.data)
        }
        if (uris.isEmpty()) return@registerForActivityResult
        val added = uris.mapNotNull { uri ->
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { /* 部分机型不支持持久化，仅本次有效 */ }
            val name = queryDisplayName(uri) ?: "本地音频${MusicManager.getPlaylist().size + 1}"
            Song(name = name, src = uri.toString(), source = SongSource.LOCAL)
        }
        if (added.isNotEmpty()) {
            MusicManager.addSongs(added)
        }
    }

    override fun initView_Xpq() {
        switch = findViewById(R.id.music_switch)
        playSwitch = findViewById(R.id.music_play_switch)
        vibrateSwitch = findViewById(R.id.music_vibrate_switch)
        vibrateDurationInput = findViewById(R.id.vibrate_duration_input)
        // 试震按钮：按当前时长触发一次震动，不依赖震动开关（与 TTS 试播一致）；提醒总开关关时由 applyBgmVisibility 置灰
        vibrateTestBtn = findViewById(R.id.btn_vibrate_test)
        vibrateTestBtn.setOnClickListener {
            MusicManager.testVibrate(MusicStore.getVibrateDuration())
        }
        loopSwitch = findViewById(R.id.music_loop_switch)
        ttsSwitch = findViewById(R.id.music_tts_switch)
        ttsInput = findViewById(R.id.tts_text_input)
        ttsTestBtn = findViewById(R.id.btn_tts_test)
        tracksArea = findViewById(R.id.tracks_area)
        tipText = findViewById(R.id.tv_tip)

        // 播放歌曲开关：纯设置，仅持久化；不绑定任何业务逻辑（受提醒总开关控制）。
        // 播放列表的显示/隐藏由本开关控制（见 applyBgmVisibility）。
        playSwitch.isChecked = MusicStore.isPlayMusicOn()
        playSwitch.setOnCheckedChangeListener { _, isOn ->
            MusicStore.setPlayMusicOn(isOn)
            applyBgmVisibility() // 播放歌曲开关变化 → 立即切换播放列表显隐
        }

        // 播放时震动开关：纯设置，仅持久化；开关切换不触发也不停止震动
        // （musicactivity 只是设置界面，实际震动由播放流程 / “试震”按钮负责）
        vibrateSwitch.isChecked = MusicStore.isVibrateOn()
        vibrateSwitch.setOnCheckedChangeListener { _, isOn ->
            MusicStore.setVibrateOn(isOn)
        }
        // 震动时长（秒）：0 = 持续循环；>0 = 按该时长震动后自动停止。纯设置，仅持久化
        vibrateDurationInput.setText(MusicStore.getVibrateDuration().takeIf { it > 0 }?.toString() ?: "")
        vibrateDurationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val sec = s?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                MusicStore.setVibrateDuration(sec)
                // 震动时长变化时，若正在震动则立即按新时长重来
                MusicManager.restartVibrate()
            }
        })

        // 顺序循环开关：纯设置
        loopSwitch.isChecked = MusicStore.isLoopOn()
        loopSwitch.setOnCheckedChangeListener { _, isOn -> MusicStore.setLoopOn(isOn) }

        // 自定义语音提醒开关 + 自定义文字：纯设置，仅持久化；实际朗读在歌曲起播/外部触发时按本开关与总开关决定
        ttsSwitch.isChecked = MusicStore.isTtsOn()
        ttsInput.setText(MusicStore.getTtsText())
        ttsSwitch.setOnCheckedChangeListener { _, isOn -> MusicStore.setTtsOn(isOn) }
        ttsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                MusicStore.setTtsText(s?.toString()?.trim() ?: "")
            }
        })

        // 测试播放：朗读输入框当前文字（不依赖播放开关 / TTS 开关，方便随时试听）
        ttsTestBtn.setOnClickListener {
            val text = ttsInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                tipText.text = "请输入要朗读的文字后再试播"
                return@setOnClickListener
            }
            TTSManager.speak(appContext, text)
        }

        // 播放列表
        adapter = PlaylistAdapter(
            onPlayToggle = { togglePlayAt(it) },
            onDelete = { deleteAt(it) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recycler_playlist)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // 长按拖动排序
        val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onMoved(
                rv: RecyclerView, vh: RecyclerView.ViewHolder,
                fromPos: Int, target: RecyclerView.ViewHolder, toPos: Int, x: Int, y: Int
            ) {
                super.onMoved(rv, vh, fromPos, target, toPos, x, y)
                MusicManager.reorder(fromPos, toPos)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) = Unit
            override fun isLongPressDragEnabled(): Boolean = true
        })
        touch.attachToRecyclerView(recycler)

        // 按钮
        findViewById<View>(R.id.btn_local).setOnClickListener {
            pickLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            })
        }
        findViewById<View>(R.id.btn_online).setOnClickListener { openPicker() }
        findViewById<View>(R.id.btn_clear).setOnClickListener {
            MusicManager.clearAll()
            MusicStore.clear()
            setBgmOn(false)
        }
        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }

        // 先按持久化状态设置总开关，务必在 attach 监听器之前（否则初始化时 isChecked 变化会触发 onBgmSwitch → 进入界面自动震动）
        bgmOn = MusicStore.isBgmOn()
        switch.isChecked = bgmOn
        switch.setOnCheckedChangeListener { _, isOn -> onBgmSwitch(isOn) }
    }

    override fun initData_Xpq() {
        MusicManager.addListener(this)
        applyBgmVisibility()

        val list = MusicStore.load()
        val idx = if (list.isEmpty()) 0 else MusicStore.getPlayIndex().coerceIn(0, list.size - 1)
        MusicManager.loadPlaylist(list, idx)
    }

    override fun onDestroy() {
        MusicManager.removeListener(this)
        super.onDestroy()
    }

    // ---------- MusicListener ----------
    override fun onMusicState(state: MusicManager.PlayState, index: Int) {
        if (state == MusicManager.PlayState.PLAYING) lastError = null
        runOnUi {
            adapter.setCurrent(index, state == MusicManager.PlayState.PLAYING)
            updateTip(MusicManager.getPlaylist(), state)
        }
    }

    override fun onMusicProgress(positionMs: Long, durationMs: Long) = Unit

    override fun onMusicPlaylist(list: List<Song>, index: Int) {
        runOnUi {
            adapter.submit(list)
            adapter.setCurrent(index, MusicManager.getState() == MusicManager.PlayState.PLAYING)
            updateTip(list, MusicManager.getState())
        }
    }

    override fun onMusicError(message: String) {
        lastError = message
        tipText.text = message
    }

    // ---------- 交互 ----------
    private fun onBgmSwitch(on: Boolean) {
        bgmOn = on
        MusicStore.setBgmOn(on)
        applyBgmVisibility()
        if (!on) {
            // 关闭总开关：停止正在进行的歌曲（总闸职责）；开启时仅改设置，不主动触发震动/播放
            MusicManager.stop()
        }
    }

    private fun setBgmOn(on: Boolean) {
        bgmOn = on
        switch.isChecked = on
        MusicStore.setBgmOn(on)
        applyBgmVisibility()
        // 仅改设置；实际震动由播放流程 / “试震”按钮触发，不在设置界面主动同步
    }

    private fun applyBgmVisibility() {
        // 播放列表的显示/隐藏由「播放歌曲」开关控制（开启才显示列表，关闭则隐藏）；其余子开关仍受提醒总开关控制
        tracksArea.visibility = if (playSwitch.isChecked) View.VISIBLE else View.GONE
        // 播放歌曲 / 震动 / 循环 / TTS 开关仅在提醒总开关开启时可操作；关闭时灰掉（即“都受总开关控制”）
        playSwitch.isEnabled = bgmOn
        vibrateSwitch.isEnabled = bgmOn
        vibrateDurationInput.isEnabled = bgmOn
        vibrateTestBtn.isEnabled = bgmOn
        loopSwitch.isEnabled = bgmOn
        ttsSwitch.isEnabled = bgmOn
        ttsInput.isEnabled = bgmOn
        ttsTestBtn.isEnabled = bgmOn
    }

    private fun openPicker() {
        val sheet = OnlinePickerBottomSheet()
        sheet.onConfirm = { chosen ->
            if (chosen.isNotEmpty()) {
                MusicManager.addSongs(chosen)
            }
        }
        sheet.show(supportFragmentManager, OnlinePickerBottomSheet.TAG)
    }

    /** 点列表某首歌：当前且正在播 → 暂停；当前且已暂停 → 从原位置继续；否则切到该首播放。
     *  按钮只控制歌曲的播放/暂停，不牵扯任何设置开关（总开关/播放歌曲开关），也不自动翻转它们。 */
    private fun togglePlayAt(pos: Int) {
        val cur = MusicManager.getCurrentIndex()
        val isCurrent = cur == pos
        when {
            isCurrent && MusicManager.getState() == MusicManager.PlayState.PLAYING -> MusicManager.pause(accompany = false) // 列表暂停只管歌曲，不牵扯震动/TTS
            isCurrent && MusicManager.getState() == MusicManager.PlayState.PAUSED -> MusicManager.resume()
            else -> MusicManager.play(pos, accompany = false) // 列表只播放歌曲，不触发震动/TTS 伴随
        }
    }

    private fun deleteAt(pos: Int) {
        MusicManager.removeAt(pos)
    }

    private fun updateTip(list: List<Song>, state: MusicManager.PlayState) {
        tipText.text = when {
            lastError != null -> lastError
            list.isEmpty() -> getString(R.string.music_tip_empty)
            state == MusicManager.PlayState.PLAYING -> getString(R.string.music_tip_playing)
            else -> getString(R.string.music_tip_not_playing)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
