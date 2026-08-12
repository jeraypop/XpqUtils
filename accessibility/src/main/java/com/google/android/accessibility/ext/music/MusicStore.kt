package com.google.android.accessibility.ext.music

import android.content.Context
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 歌单本地持久化（SharedPreferences + gson）。
 * 在线歌曲以直链持久化；本地歌曲持久化 content:// Uri（已 takePersistableUriPermission，可跨重启访问）。
 */
object MusicStore {
    private const val PREFS = "xpq_music"
    private const val KEY_PLAYLIST = "playlist"
    private const val KEY_BGM_ON = "bgm_on"
    private const val KEY_VIBRATE_ON = "vibrate_on"
    private const val KEY_LOOP_ON = "loop_on"
    private const val KEY_TTS_ON = "tts_on"
    private const val KEY_TTS_TEXT = "tts_text"
    private const val KEY_PLAY_INDEX = "play_index"

    fun load(): List<Song> {
        return try {
            val sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = sp.getString(KEY_PLAYLIST, null) ?: return emptyList()
            val type = object : TypeToken<List<Song>>() {}.type
            Gson().fromJson<List<Song>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Song>) {
        try {
            val sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_PLAYLIST, Gson().toJson(list)).apply()
        } catch (_: Exception) {
        }
    }

    fun clear() {
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PLAYLIST).apply()
        } catch (_: Exception) {
        }
    }

    /** 背景音乐开关偏好（仿小程序 K_BGM） */
    fun isBgmOn(): Boolean =
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BGM_ON, false)
        } catch (_: Exception) {
            false
        }

    fun setBgmOn(on: Boolean) {
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BGM_ON, on).apply()
        } catch (_: Exception) {
        }
    }

    /** 播放时震动偏好（仅在播放开关开启时生效） */
    fun isVibrateOn(): Boolean =
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VIBRATE_ON, false)
        } catch (_: Exception) {
            false
        }

    fun setVibrateOn(on: Boolean) {
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VIBRATE_ON, on).apply()
        } catch (_: Exception) {
        }
    }

    /** 顺序循环偏好（默认关：播完列表最后一首即停止；开启则回到第一首循环） */
    fun isLoopOn(): Boolean =
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOOP_ON, false)
        } catch (_: Exception) {
            false
        }

    fun setLoopOn(on: Boolean) {
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOOP_ON, on).apply()
        } catch (_: Exception) {
        }
    }

    /** TTS 播报自定义文字开关（仅在提醒总开关开启时生效） */
    fun isTtsOn(): Boolean =
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TTS_ON, false)
        } catch (_: Exception) {
            false
        }

    fun setTtsOn(on: Boolean) {
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TTS_ON, on).apply()
        } catch (_: Exception) {
        }
    }

    /** TTS 要朗读的自定义文字（空则不播报） */
    fun getTtsText(): String =
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TTS_TEXT, "") ?: ""
        } catch (_: Exception) {
            ""
        }

    fun setTtsText(text: String) {
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TTS_TEXT, text).apply()
        } catch (_: Exception) {
        }
    }

    /** 当前播放序号（仿小程序 K_PLAY_IDX），重启后恢复 */
    fun getPlayIndex(): Int =
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PLAY_INDEX, 0)
        } catch (_: Exception) {
            0
        }

    fun setPlayIndex(index: Int) {
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PLAY_INDEX, index).apply()
        } catch (_: Exception) {
        }
    }
}
