package com.google.android.accessibility.ext.window
import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener

import com.android.accessibility.ext.databinding.LogOverlayXpqBinding

import com.blankj.utilcode.util.ScreenUtils
import com.google.android.accessibility.ext.AssistsServiceListener
import com.google.android.accessibility.ext.CoroutineWrapper
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@SuppressLint("StaticFieldLeak")
object OverlayLog : AssistsServiceListener {

    var runAutoScrollListJob: Job? = null
    private var logCollectJob: Job? = null

    private val onScrollTouchListener = object : OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    runAutoScrollListJob?.cancel()
                    runAutoScrollListJob = null
                }

                MotionEvent.ACTION_UP -> {
                    //滚动监听
                    //runAutoScrollList()
                }
            }
            return false
        }
    }

    // ---------- 视图绑定 ----------
    private var viewBinding: LogOverlayXpqBinding? = null
        @SuppressLint("ClickableViewAccessibility")
        get() {
            if (field == null) {
                var context: Context? = SelectToSpeakServiceAbstract.instance
                if (context==null){
                    context = appContext
                }

                field = LogOverlayXpqBinding.inflate(LayoutInflater.from(context)).apply {
                    scrollView.setOnTouchListener(onScrollTouchListener)
                    btnCopy.setOnClickListener {
                        CoroutineWrapper.launch { LogWrapper.copyLogMethod() }
                    }
                    btnClean.setOnClickListener {
                        CoroutineWrapper.launch { LogWrapper.clearLog() }
                    }
                    btnStop.setOnClickListener {
                        CoroutineWrapper.launch {
                            hide()
                        }
                    }
                }
            }
            return field
        }


    var onClose: ((parent: View) -> Unit)? = null

    val showed: Boolean
        get() = assistWindowWrapper?.let {
            AssistsWindowManager.isVisible(it.getView())
        } ?: false


    var assistWindowWrapper: AssistsWindowWrapper? = null
        private set
        get() {
            viewBinding?.let {
                if (field == null) {
                    field = AssistsWindowWrapper(it.root, wmLayoutParams = AssistsWindowManager.createLayoutParams().apply {
                        width = (ScreenUtils.getScreenWidth() * 0.8).toInt()
                        height = (ScreenUtils.getScreenHeight() * 0.5).toInt()
                    }, onClose = { hide() }).apply {
                        minWidth = (ScreenUtils.getScreenWidth() * 0.6).toInt()
                        minHeight = (ScreenUtils.getScreenHeight() * 0.4).toInt()
                        initialCenter = true
                        viewBinding.tvTitle.text = "日志"
                    }
                }
            }
            return field
        }


    // ---------- 智能线程检测版 show ----------
    fun show() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            internalShow()
        } else {
            CoroutineWrapper.launch(isMain = true) {
                internalShow()
            }
        }
    }

    private fun internalShow() {
        if (!SelectToSpeakServiceAbstract.listeners.contains(this)) {
            SelectToSpeakServiceAbstract.listeners.add(this)
        }
        if (!AssistsWindowManager.contains(assistWindowWrapper?.getView())) {
            AssistsWindowManager.add(assistWindowWrapper)
            initLogCollect()
            runAutoScrollList(delay = 0)
        }
    }

    // ---------- 智能线程检测版 hide ----------
    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            internalHide()
        } else {
            CoroutineWrapper.launch(isMain = true) {
                internalHide()
            }
        }
    }

    private fun internalHide() {
        AssistsWindowManager.removeView(assistWindowWrapper?.getView())
        logCollectJob?.cancel()
        runAutoScrollListJob?.cancel()
        logCollectJob = null
        runAutoScrollListJob = null
    }

    // ---------- 智能线程检测版 onUnbind ----------
    override fun onUnbind() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            internalUnbind()
        } else {
            CoroutineWrapper.launch(isMain = true) {
                internalUnbind()
            }
        }
    }

    private fun internalUnbind() {
        try {
            AssistsWindowManager.removeView(assistWindowWrapper?.getView())
        } catch (_: Exception) {
        }

        viewBinding = null
        assistWindowWrapper = null
        logCollectJob?.cancel()
        logCollectJob = null
        runAutoScrollListJob?.cancel()
        runAutoScrollListJob = null
    }

/*    fun show() {
        if (!SelectToSpeakServiceAbstract.listeners.contains(this)) {
            SelectToSpeakServiceAbstract.listeners.add(this)
        }
        if (!AssistsWindowManager.contains(assistWindowWrapper?.getView())) {
            AssistsWindowManager.add(assistWindowWrapper)
            initLogCollect()
            runAutoScrollList(delay = 0)
        }
    }

    fun hide() {
        AssistsWindowManager.removeView(assistWindowWrapper?.getView())
        logCollectJob?.cancel()
        logCollectJob = null
        runAutoScrollListJob?.cancel()
        runAutoScrollListJob = null
    }

    override fun onUnbind() {
        viewBinding = null
        assistWindowWrapper = null
        logCollectJob?.cancel()
        logCollectJob = null
        runAutoScrollListJob?.cancel()
        runAutoScrollListJob = null
    }*/


    private fun runAutoScrollList(delay: Long = 5000) {
        runAutoScrollListJob?.cancel()
        runAutoScrollListJob = CoroutineWrapper.launch {
            delay(delay)
            while (true) {
                withContext(Dispatchers.Main) {
                    viewBinding?.scrollView?.smoothScrollBy(0, viewBinding?.scrollView?.getChildAt(0)?.height ?: 0)
                }
                delay(250)
            }
        }
    }

    private fun initLogCollect() {
        logCollectJob?.cancel()
        logCollectJob = CoroutineWrapper.launch {
            withContext(Dispatchers.Main) {
                viewBinding?.apply {
                    tvLog.text = LogWrapper.logCache
                    tvLength.text = "${tvLog.length()}"
                }
            }
            LogWrapper.logAppendValue.collect {
                withContext(Dispatchers.Main) {
                    viewBinding?.apply {
                        tvLog.text = LogWrapper.logCache
                        tvLength.text = "${tvLog.length()}"
                    }
                }
            }
        }
    }
}
