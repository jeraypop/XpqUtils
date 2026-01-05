package com.google.android.accessibility.ext.utils

import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OneShotContinuation<T>(
    private val cont: CancellableContinuation<T>
) {
    private val finished = AtomicBoolean(false)

    fun finish(value: T) {
        if (finished.compareAndSet(false, true)) {
            cont.resume(value)
        }
    }

    fun finishWithException(t: Throwable) {
        if (finished.compareAndSet(false, true)) {
            cont.resumeWithException(t)
        }
    }

    fun isFinished(): Boolean = finished.get()
}
