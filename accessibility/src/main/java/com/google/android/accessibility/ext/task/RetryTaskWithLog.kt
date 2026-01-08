package com.google.android.accessibility.ext.task

import android.util.Log
import com.google.android.accessibility.ext.task.i.ITaskTracker
import com.google.android.accessibility.ext.utils.KeyguardUnLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

private const val TAG = "LogTracker"
const val TIMEOUT = 5_000L
const val HALF_TIMEOUT = 5_000L
const val PERIOD = 200L

suspend fun <T>retryTaskWithLog(
    taskName: String,
    timeOutMillis: Long = TIMEOUT,
    periodMillis: Long = PERIOD,
    predicate: suspend CoroutineScope.() -> T?
): T? {
    return try {
        retryTask(timeOutMillis, periodMillis, LogTracker(taskName), predicate)
    } catch (e: Exception) {
        null
    }
}

/*
* timeOutMillis（默认10秒）是任务执行总超时时间，
* periodMillis（默认500毫秒）是重试间隔时间。
* 二者共同控制异步任务的重试策略，实现"每隔0.5秒检查一次，
* 最长等待10秒"的轮询机制。
*
*
* */
suspend fun retryCheckTaskWithLog(
    taskName: String,
    timeOutMillis: Long = TIMEOUT,
    periodMillis: Long = PERIOD,
    hasLog: Boolean = false,
    predicate: suspend CoroutineScope.() -> Boolean

): Boolean {
    return try {
        retryCheckTask(timeOutMillis, periodMillis, LogTracker(taskName,hasLog), predicate)
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}




class LogTracker<T>(private val taskName: String,private val hasLog: Boolean = false) : ITaskTracker<T> {

    override fun onStart() {
        if(hasLog)KeyguardUnLock.sendLog("【$taskName】开始循环执行")
        Log.d(TAG, "【$taskName】开始执行")
    }

    override fun onEach(currentCount: Int) {
        if(hasLog)KeyguardUnLock.sendLog("【$taskName】第 $currentCount 次执行")
        Log.d(TAG, "【$taskName】第 $currentCount 次执行")
    }

    override fun onSuccess(result: T, executeDuration: Long, executeCount: Int) {
        if(hasLog)KeyguardUnLock.sendLog("【$taskName】任务执行成功，轮循总次数：${executeCount}, 耗时：$executeDuration ms")
        Log.d(TAG, "【$taskName】任务执行成功，轮询总次数：${executeCount}, 耗时：$executeDuration ms")
    }

    override fun onError(error: Throwable, executeDuration: Long, executeCount: Int) {
        if(hasLog)KeyguardUnLock.sendLog("【$taskName】执行异常【${error.message}】，轮循总次数：${executeCount}, 耗时：$executeDuration ms")
        Log.d(TAG, "【$taskName】执行异常【${error.message}】，轮循总次数：${executeCount}, 耗时：$executeDuration ms")
    }

}