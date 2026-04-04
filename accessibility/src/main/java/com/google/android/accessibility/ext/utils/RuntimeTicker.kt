package com.google.android.accessibility.ext.utils



import android.content.Context
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.accessibility.ext.utils.RuntimeTracker.formatDurationWithSeconds
import kotlinx.coroutines.*

class RuntimeTicker(
    private val context: Context,
    private val textView: TextView,
    private val type: Type = Type.PROCESS
) : DefaultLifecycleObserver {

    enum class Type {
        PROCESS, FOREGROUND, BACKGROUND
    }

    private var job: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        start()
    }

    override fun onStop(owner: LifecycleOwner) {
        stop()
    }

    private fun start() {
        if (job != null) return

        job = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val time = when (type) {
                    Type.PROCESS -> RuntimeTracker.getProcessRuntime(context)
                    Type.FOREGROUND -> RuntimeTracker.getForegroundTime(context)
                    Type.BACKGROUND -> RuntimeTracker.getBackgroundTime(context)
                }

                textView.text = formatDurationWithSeconds(time)

                delay(1000)
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
    }
}