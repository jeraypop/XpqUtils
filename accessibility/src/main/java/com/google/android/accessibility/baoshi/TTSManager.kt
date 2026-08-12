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
    /** 引擎尚未就绪时暂存待播文字，onInit 成功后补播（解决首次点击不播报）。 */
    private var pendingText: String? = null
    @JvmOverloads
    @JvmStatic
    fun speak(context: Context = appContext, text: String) {

        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }

        if (ready) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "time")
        } else {
            // 引擎仍在异步初始化：先暂存，待 onInit 成功后再播（连续点击只保留最后一次）
            pendingText = text
        }
    }

    /** 停止当前朗读（保留 TTS 引擎，可再次 speak）。用于播放暂停/停止时立即停止播报。 */
    @JvmStatic
    fun stop() {
        try { tts?.stop() } catch (_: Exception) { }
        pendingText = null
    }

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts?.language = Locale.CHINA
            // 初始化完成：若有等待播报的文字，立即补播（首次点击场景）
            val pending = pendingText
            pendingText = null
            if (pending != null) {
                tts?.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "time")
            }
        }
    }
}