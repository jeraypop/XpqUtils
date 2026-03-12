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

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts?.language = Locale.CHINA
        }
    }
}