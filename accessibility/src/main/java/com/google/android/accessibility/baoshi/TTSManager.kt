package com.google.android.accessibility.baoshi

import android.content.Context
import android.speech.tts.TextToSpeech
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.util.Locale

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/3/11  10:59
 * Description:This is TTSManager
 */
object TTSManager : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    @JvmOverloads
    @JvmStatic
    fun speak(context: Context = appContext, text: String) {

        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }

        if (ready) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "time")
        }
    }

    /** 停止当前朗读（保留 TTS 引擎，可再次 speak）。用于播放暂停/停止时立即停止播报。 */
    @JvmStatic
    fun stop() {
        try { tts?.stop() } catch (_: Exception) { }
    }

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts?.language = Locale.CHINA
        }
    }
}