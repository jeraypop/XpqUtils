package com.google.android.accessibility.ext.utils.verificationcode

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import java.util.Locale
import java.util.UUID

/**
 * Company    : 
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/6/5  21:13
 * Description:This is LoginConfig
 */
object LoginConfig {
    private const val SP_NAME = "sp_name_wxrp"
    private const val KEY_AUTO_FILL = "key_open_yan_zheng_ma"
    private const val KEY_SCHEME = "scheme"
    private const val KEY_VOICE_READ = "yuyinbobaoyanzheng"
    private const val KEY_VOICE_READ_TWO = "yuyinbobaoyanzhengtwo"
    private const val KEY_SHOW_CODETOAST = "KEY_SHOW_CODETOAST"

    private fun sp(context: Context = appContext) =
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    private fun spvoice(context: Context = appContext) =
        context.getSharedPreferences(appContext.packageName, Context.MODE_PRIVATE)

    fun isAutoFillEnabled(context: Context = appContext): Boolean {
        return sp(context).getBoolean(KEY_AUTO_FILL, false)
    }

    fun setAutoFillEnabled(
        context: Context = appContext,
        enabled: Boolean
    ) {
        sp(context)
            .edit()
            .putBoolean(KEY_AUTO_FILL, enabled)
            .apply()
    }

    fun isAutoShowCodeFloat(context: Context = appContext): Boolean {
        return sp(context).getBoolean(KEY_SHOW_CODETOAST, true)
    }

    fun setAutoShowCodeFloat(
        context: Context = appContext,
        enabled: Boolean
    ) {
        sp(context)
            .edit()
            .putBoolean(KEY_SHOW_CODETOAST, enabled)
            .apply()
    }

    fun isVoiceReadEnabled(context: Context = appContext): Boolean {
        return spvoice(context).getBoolean(KEY_VOICE_READ, false)
    }

    fun setVoiceReadEnabled(
        context: Context = appContext,
        enabled: Boolean
    ) {
        spvoice(context)
            .edit()
            .putBoolean(KEY_VOICE_READ, enabled)
            .apply()
    }

    fun isVoiceReadEnabledTwo(context: Context = appContext): Boolean {
        return spvoice(context).getBoolean(KEY_VOICE_READ_TWO, true)
    }

    fun setVoiceReadEnabledTwo(
        context: Context = appContext,
        enabled: Boolean
    ) {
        spvoice(context)
            .edit()
            .putBoolean(KEY_VOICE_READ_TWO, enabled)
            .apply()
    }

    fun getScheme(context: Context = appContext): Int {
        return sp(context).getInt(KEY_SCHEME, 1)
    }

    fun setScheme(
        context: Context = appContext,
        scheme: Int
    ) {
        sp(context)
            .edit()
            .putInt(KEY_SCHEME, scheme)
            .apply()
    }
    @JvmOverloads
    @JvmStatic
    fun playYanZhenMa(s: String) {
        //处理小米手机按照 金额播报的问题
        val sb = StringBuilder()
        for (c in s) {
            sb.append("[" + c + "]")
        }
        playTTS_XPQ(appContext, "本次验证码为:" + sb.toString())
        if (isVoiceReadEnabledTwo()){
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(
                Runnable {
                    //要执行的操作
                    playTTS_XPQ(appContext, sb.toString())
                }, 5000) //5秒后执行Runnable中的run方法
        }



    }

    private var lastSpeakText = ""
    private var lastSpeakTime = 0L
    @JvmOverloads
    @JvmStatic
    fun playTTS_XPQ(context: Context = appContext, text: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (
                text == lastSpeakText &&
                now - lastSpeakTime < 5_000
            ) {
                Log.e("TTS", "忽略重复播报:$text")
                return
            }
            lastSpeakText = text
            lastSpeakTime = now
        }

        var localTts: TextToSpeech? = null
        localTts = TextToSpeech(context.applicationContext) { status ->
            val tts = localTts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                tts.shutdown()
                return@TextToSpeech
            }
            val result = tts.setLanguage(Locale.CHINESE)
            if (result == null ||
                result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts.shutdown()
                return@TextToSpeech
            }
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(utteranceId: String?) {
                        // 开始播报
                    }

                    override fun onDone(utteranceId: String?) {
                        // 播报完成
                        Handler(Looper.getMainLooper())
                            .post {
                                localTts?.stop()
                                localTts?.shutdown()
                                localTts = null
                            }
                    }

                    override fun onError(utteranceId: String?) {
                        // 播报失败
                        Handler(Looper.getMainLooper()).post {

                            localTts?.stop()
                            localTts?.shutdown()
                            localTts = null
                        }
                    }
                }
            )

            val params: Bundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Bundle().apply {
                    //STREAM_MUSIC 音乐频道
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
                }
            } else null
            Log.e("验证码为:", "2 " + text )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                //QUEUE_FLUSH 换原有文字
                //QUEUE_ADD 会将加入队列的待播报文字按顺序播放
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params,  UUID.randomUUID().toString())
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }

}