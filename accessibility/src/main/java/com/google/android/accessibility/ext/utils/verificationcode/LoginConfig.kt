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

    private fun sp(context: Context = appContext) =
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    private fun spvoice(context: Context = appContext) =
        context.getSharedPreferences(appContext.packageName, Context.MODE_PRIVATE)

    fun isAutoFillEnabled(context: Context = appContext): Boolean {
        return sp(context).getBoolean(KEY_AUTO_FILL, true)
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

    fun isVoiceReadEnabled(context: Context = appContext): Boolean {
        return spvoice(context).getBoolean(KEY_VOICE_READ, true)
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

    fun playYanZhenMa(s: String) {
        //处理小米手机按照 金额播报的问题
        val sb = StringBuilder()
        for (c in s) {
            sb.append("[" + c + "]")
        }
        //playQiangTiXing(appContext, "本次验证码为:" + sb.toString())
        playTTS_XPQ(appContext, "本次验证码为:" + sb.toString())
        if (isVoiceReadEnabledTwo()){
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(
                Runnable {
                    //要执行的操作
                    playTTS_XPQ(appContext, sb.toString())
                }, 5000) //3秒后执行Runnable中的run方法
        }



    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts_xpq: TextToSpeech? = null

    @JvmStatic
    fun playTTS_XPQ(context: Context, text: String) {

        stopTTS_XPQ()

        tts_xpq = TextToSpeech(context) { status ->
            Log.e("验证码为:", " " +status )
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech

            val result = tts_xpq?.setLanguage(Locale.CHINESE)
            Log.e("验证码为:", " " +result )
            if (result == null ||
                result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                return@TextToSpeech
            }
            Log.e("验证码为:", " " + text  )
            tts_xpq?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(utteranceId: String?) {
                        // 开始播报
                    }

                    override fun onDone(utteranceId: String?) {
                        // 播报完成
                        mainHandler.postDelayed({

                            stopTTS_XPQ()

                        }, 1000)
                    }

                    override fun onError(utteranceId: String?) {
                        // 播报失败
                        stopTTS_XPQ()
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
                tts_xpq?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "1")
            } else {
                tts_xpq?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }

    @JvmStatic
    fun stopTTS_XPQ() {
        tts_xpq?.stop()
        tts_xpq?.shutdown()
        tts_xpq = null
    }

}