package com.google.android.accessibility.ext.window

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener

import com.android.accessibility.ext.databinding.LogOverlayBinding

import com.blankj.utilcode.util.ScreenUtils
import com.google.android.accessibility.ext.AssistsServiceListener
import com.google.android.accessibility.ext.CoroutineWrapper
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
//                    runAutoScrollList()
                }
            }
            return false
        }
    }
    private var viewBinding: LogOverlayBinding? = null
        @SuppressLint("ClickableViewAccessibility")
        get() {
            if (field == null) {
                field = LogOverlayBinding.inflate(LayoutInflater.from(SelectToSpeakServiceAbstract.instance)).apply {
                    scrollView.setOnTouchListener(onScrollTouchListener)
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

    var showed = false
        private set
        get() {
            assistWindowWrapper?.let {
                return AssistsWindowManager.isVisible(it.getView())
            } ?: return false
        }

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

    fun show() {
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
    }


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